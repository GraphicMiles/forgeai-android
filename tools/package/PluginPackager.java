package ai.luna.tools;

import ai.luna.contracts.PluginManifest;
import ai.luna.runtime.PluginVerifier;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a plugin source document into a signed {@code .lunapkg.json}.
 *
 * <p>This is the packaging half of the plugin format, and it deliberately uses
 * the app's own classes to do it: {@link PluginManifest#canonicalContent()}
 * decides what is hashed and {@link PluginVerifier} decides what is acceptable.
 * A separate re-implementation of the canonical form — in Python, in a script,
 * anywhere — would be a second definition of the truth, and the day the two
 * disagreed every plugin in the world would stop installing.
 *
 * <pre>
 *   sign   &lt;source.json&gt; &lt;out.lunapkg.json&gt; [--key key.b64]
 *   verify &lt;file.lunapkg.json&gt; [--strict]
 * </pre>
 *
 * <p>The signing key is an RSA private key, base64 PKCS#8, in a plain file. If
 * the named file does not exist a new pair is generated and written there, so
 * a first-time author does not have to know what any of that means. Nothing in
 * the repository holds a private key: the examples are re-signed whenever they
 * are rebuilt, and a signature is only ever checked against the public key the
 * package carries.
 */
public final class PluginPackager {

    public static void main(String[] arguments) throws Exception {
        List<String> args = new ArrayList<>();
        for (String argument : arguments) {
            args.add(argument);
        }
        if (args.size() < 2) {
            System.err.println("usage: sign <source.json> <out.lunapkg.json> [--key key.b64]");
            System.err.println("       verify <file.lunapkg.json> [--strict]");
            System.exit(2);
        }
        String command = args.get(0);
        if ("sign".equals(command)) {
            sign(args);
        } else if ("verify".equals(command)) {
            verify(args);
        } else {
            System.err.println("Unknown command: " + command);
            System.exit(2);
        }
    }

    // --- signing ----------------------------------------------------------------

    private static void sign(List<String> args) throws Exception {
        File source = new File(args.get(1));
        File target = new File(args.size() > 2 && !args.get(2).startsWith("--")
            ? args.get(2) : args.get(1).replace(".json", ".lunapkg.json"));
        File keyFile = new File(option(args, "--key", "build/luna-signing-key.b64"));

        JSONObject document = new JSONObject(read(source));
        document.remove("signing");

        PluginManifest unsigned = PluginManifest.fromJson(document);
        String digest = PluginVerifier.sha256(unsigned.canonicalContent());

        KeyPair pair = keyPair(keyFile);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(digest.getBytes(Charset.forName("UTF-8")));

        JSONObject signing = new JSONObject();
        signing.put("digest", digest);
        signing.put("signature", base64(signer.sign()));
        signing.put("publicKey", base64(pair.getPublic().getEncoded()));
        document.put("signing", signing);

        // Read the finished package back the way the device will read it, and
        // refuse to write one that would be refused on arrival.
        PluginManifest signed = PluginManifest.fromJson(document);
        String refusal = new PluginVerifier().refuse(signed);
        if (refusal != null) {
            System.err.println("Refused: " + refusal);
            System.exit(1);
        }

        write(target, document.toString(2) + "\n");
        System.out.println("Signed " + signed.id + " " + signed.version
            + " (" + signed.contentCount() + " documents) -> " + target.getPath());
    }

    // --- verifying --------------------------------------------------------------

    private static void verify(List<String> args) throws Exception {
        File file = new File(args.get(1));
        boolean strict = args.contains("--strict");
        PluginManifest manifest = PluginManifest.fromJson(new JSONObject(read(file)));
        PluginVerifier verifier = new PluginVerifier().allowUnsigned(!strict);
        String refusal = verifier.refuse(manifest);
        if (refusal != null) {
            System.err.println(file.getName() + ": " + refusal);
            System.exit(1);
        }
        System.out.println(file.getName() + ": " + manifest.id + " " + manifest.version
            + (manifest.signed() ? " - signed, verified" : " — unsigned"));
    }

    // --- keys -------------------------------------------------------------------

    /** The pair in that file, or a new pair written there. */
    private static KeyPair keyPair(File file) throws Exception {
        if (file.isFile()) {
            byte[] encoded = decode64(read(file).trim());
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
            java.security.interfaces.RSAPrivateCrtKey crt =
                (java.security.interfaces.RSAPrivateCrtKey) privateKey;
            java.security.PublicKey publicKey = factory.generatePublic(
                new java.security.spec.RSAPublicKeySpec(crt.getModulus(),
                    crt.getPublicExponent()));
            return new KeyPair(publicKey, privateKey);
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(3072);
        KeyPair pair = generator.generateKeyPair();
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        write(file, base64(pair.getPrivate().getEncoded()) + "\n");
        System.out.println("New signing key written to " + file.getPath()
            + " - keep it, and keep it private.");
        return pair;
    }

    // --- plumbing ---------------------------------------------------------------

    private static String option(List<String> args, String name, String fallback) {
        int index = args.indexOf(name);
        return index >= 0 && index + 1 < args.size() ? args.get(index + 1) : fallback;
    }

    private static String read(File file) throws Exception {
        InputStream in = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), Charset.forName("UTF-8"));
        } finally {
            in.close();
        }
    }

    private static void write(File file, String text) throws Exception {
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes(Charset.forName("UTF-8")));
        } finally {
            out.close();
        }
    }

    /** Base64 by hand, to match the decoder the runtime ships with. */
    static String base64(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index + 2 < bytes.length) {
            int chunk = ((bytes[index] & 0xFF) << 16)
                | ((bytes[index + 1] & 0xFF) << 8)
                | (bytes[index + 2] & 0xFF);
            out.append(ALPHABET.charAt((chunk >> 18) & 0x3F));
            out.append(ALPHABET.charAt((chunk >> 12) & 0x3F));
            out.append(ALPHABET.charAt((chunk >> 6) & 0x3F));
            out.append(ALPHABET.charAt(chunk & 0x3F));
            index += 3;
        }
        int left = bytes.length - index;
        if (left == 1) {
            int chunk = (bytes[index] & 0xFF) << 16;
            out.append(ALPHABET.charAt((chunk >> 18) & 0x3F));
            out.append(ALPHABET.charAt((chunk >> 12) & 0x3F));
            out.append("==");
        } else if (left == 2) {
            int chunk = ((bytes[index] & 0xFF) << 16) | ((bytes[index + 1] & 0xFF) << 8);
            out.append(ALPHABET.charAt((chunk >> 18) & 0x3F));
            out.append(ALPHABET.charAt((chunk >> 12) & 0x3F));
            out.append(ALPHABET.charAt((chunk >> 6) & 0x3F));
            out.append('=');
        }
        return out.toString();
    }

    /** And back again, so a key file written here can be read here. */
    static byte[] decode64(String text) {
        String clean = text.replace("\n", "").replace("\r", "").replace("=", "").trim();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (int index = 0; index < clean.length(); index++) {
            int value = ALPHABET.indexOf(clean.charAt(index));
            if (value < 0) {
                throw new IllegalArgumentException("not base64");
            }
            buffer = (buffer << 6) | value;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buffer >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }

    private static final String ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private PluginPackager() {
    }
}
