package ai.luna.runtime;

import ai.luna.contracts.Capability;
import ai.luna.contracts.PluginManifest;

import java.nio.charset.Charset;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a plugin may be installed at all.
 *
 * <p>Everything here fails closed and says why in a sentence a person can act
 * on. There is no partial install: a plugin whose third skill is malformed does
 * not arrive with two skills, it does not arrive.
 *
 * <p>What is checked, in order: the format is one this runtime understands; the
 * id is a real id; it carries something; it is not enormous; every capability
 * it asks for exists and is one a plugin may ever have; the content matches its
 * digest; and the signature belongs to a key we trust — unless unsigned plugins
 * have been explicitly allowed, which is a developer setting and nothing else.
 */
public final class PluginVerifier {

    /** Documents one plugin may carry. A limit is a defence, not a rule of taste. */
    private static final int MAX_DOCUMENTS = 200;

    /** Base64 X.509 public keys whose signatures are accepted. */
    private final Set<String> trusted = new HashSet<>();

    private boolean allowUnsigned;

    public PluginVerifier trust(Collection<String> keys) {
        trusted.clear();
        if (keys != null) {
            for (String key : keys) {
                if (key != null && !key.trim().isEmpty()) {
                    trusted.add(key.trim());
                }
            }
        }
        return this;
    }

    /** Developer mode: install a plugin nobody signed. Off by default. */
    public PluginVerifier allowUnsigned(boolean allow) {
        this.allowUnsigned = allow;
        return this;
    }

    /** The refusal, or null when the plugin may be installed. */
    public String refuse(PluginManifest manifest) {
        if (manifest == null) {
            return "There is nothing to install.";
        }
        if (manifest.format != PluginManifest.FORMAT) {
            return "This plugin was built for a different version of Luna.";
        }
        if (!validId(manifest.id)) {
            return "That plugin has no usable id.";
        }
        if (manifest.version.isEmpty()) {
            return "That plugin does not say which version it is.";
        }
        if (manifest.contentCount() == 0) {
            return "That plugin contains nothing.";
        }
        if (manifest.contentCount() > MAX_DOCUMENTS) {
            return "That plugin carries far too much to be one plugin.";
        }
        String capability = badCapability(manifest.capabilities);
        if (capability != null) {
            return capability;
        }
        String content = badContent(manifest);
        if (content != null) {
            return content;
        }
        if (!digestMatches(manifest)) {
            return "That plugin does not match its own checksum, so it was changed on the way.";
        }
        if (!manifest.signed()) {
            return allowUnsigned ? null : "That plugin is not signed.";
        }
        if (!trusted.isEmpty() && !trusted.contains(manifest.publicKey)) {
            return "That plugin was signed by somebody this device does not trust.";
        }
        return signatureValid(manifest) ? null : "That plugin's signature is not valid.";
    }

    public boolean accepts(PluginManifest manifest) {
        return refuse(manifest) == null;
    }

    /** Lower case, dotted, no surprises. Ids end up in prompts and in paths. */
    private boolean validId(String id) {
        if (id == null || id.length() < 3 || id.length() > 64) {
            return false;
        }
        for (int index = 0; index < id.length(); index++) {
            char letter = id.charAt(index);
            boolean ok = (letter >= 'a' && letter <= 'z')
                || (letter >= '0' && letter <= '9')
                || letter == '.' || letter == '-' || letter == '_';
            if (!ok) {
                return false;
            }
        }
        return !id.startsWith("core") && !id.equals("luna");
    }

    private String badCapability(List<String> capabilities) {
        for (String capability : capabilities) {
            if (!Capability.isKnown(capability)) {
                return "That plugin asks for something this runtime has no name for: "
                    + capability + ".";
            }
            if (!Capability.grantableToPlugin(capability)) {
                return "That plugin asks to " + Capability.describe(capability).toLowerCase(
                    Locale.US) + ", which no plugin is ever allowed to do.";
            }
        }
        return null;
    }

    /** Content has to be shaped like content, and owned by this plugin. */
    private String badContent(PluginManifest manifest) {
        List<String> ids = new ArrayList<>();
        for (org.json.JSONObject skill : manifest.skills) {
            String id = skill.optString("id", "");
            if (id.isEmpty() || skill.optString("instructions", "").isEmpty()) {
                return "One of that plugin's skills is incomplete.";
            }
            if (!owns(manifest.id, id)) {
                return "A plugin may only name its own skills. " + id + " is not its to define.";
            }
            ids.add(id);
        }
        for (org.json.JSONObject agent : manifest.agents) {
            String id = agent.optString("id", "");
            if (id.isEmpty()) {
                return "One of that plugin's agents has no id.";
            }
            if (!owns(manifest.id, id)) {
                return "A plugin may only name its own agents. " + id + " is not its to define.";
            }
            if (agent.optBoolean("builtIn", false)) {
                return "A plugin cannot install an agent that claims to ship with Luna.";
            }
            ids.add(id);
        }
        for (org.json.JSONObject workflow : manifest.workflows) {
            String id = workflow.optString("id", "");
            if (id.isEmpty() || !owns(manifest.id, id)) {
                return "A plugin may only name its own workflows.";
            }
            ids.add(id);
        }
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) {
                return "That plugin defines " + id + " twice.";
            }
        }
        return null;
    }

    /** Everything a plugin defines is named after it, so nothing collides. */
    private boolean owns(String pluginId, String documentId) {
        return documentId.equals(pluginId) || documentId.startsWith(pluginId + ".");
    }

    private boolean digestMatches(PluginManifest manifest) {
        if (manifest.digest.isEmpty()) {
            return false;
        }
        return manifest.digest.equals(sha256(manifest.canonicalContent()));
    }

    private boolean signatureValid(PluginManifest manifest) {
        try {
            byte[] key = base64(manifest.publicKey);
            byte[] signature = base64(manifest.signature);
            PublicKey publicKey = KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(key));
            java.security.Signature verifier = java.security.Signature.getInstance(
                "SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(manifest.digest.getBytes(Charset.forName("UTF-8")));
            return verifier.verify(signature);
        } catch (Exception error) {
            // A signature that cannot be parsed is a signature that failed.
            return false;
        }
    }

    /** SHA-256 as lower-case hex. */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(Charset.forName("UTF-8")));
            StringBuilder out = new StringBuilder();
            for (byte value : bytes) {
                out.append(Character.forDigit((value >> 4) & 0xF, 16));
                out.append(Character.forDigit(value & 0xF, 16));
            }
            return out.toString();
        } catch (Exception error) {
            return "";
        }
    }

    /**
     * Base64, decoded by hand.
     *
     * <p>{@code java.util.Base64} is API 26 and this app runs from 24;
     * {@code android.util.Base64} does not exist on the JVM these classes are
     * tested on. Twenty lines is cheaper than either compromise.
     */
    static byte[] base64(String text) {
        String clean = text.replace("\n", "").replace("\r", "").trim();
        int padding = 0;
        while (padding < 2 && clean.endsWith("=")) {
            clean = clean.substring(0, clean.length() - 1);
            padding++;
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
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
}
