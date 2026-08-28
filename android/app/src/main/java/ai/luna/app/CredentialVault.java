package ai.luna.app;

import ai.luna.contracts.SecretProvider;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Secrets live in the Android keystore, not in a prompt and not in plain
 * preferences. The ciphertext is stored locally; the key never leaves the chip.
 */
public final class CredentialVault implements SecretProvider {

    @Override
    public String id() {
        return "android.keystore";
    }

    /**
     * One namespace per owner. The core keeps the names it always used, so
     * nothing already stored moves; a plugin gets its id in front of the key
     * and cannot name its way into anybody else's.
     */
    public static String scoped(String owner, String key) {
        String who = owner == null ? "" : owner.trim();
        String what = key == null ? "" : key.trim();
        if (who.isEmpty() || who.equals(CORE)) {
            return what;
        }
        return "plugin:" + who + "/" + what;
    }

    @Override
    public void put(String owner, String key, String value) throws Exception {
        store(scoped(owner, key), value);
    }

    @Override
    public String get(String owner, String key) {
        return read(scoped(owner, key));
    }

    @Override
    public boolean has(String owner, String key) {
        return has(scoped(owner, key));
    }

    @Override
    public void remove(String owner, String key) {
        clear(scoped(owner, key));
    }


    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "luna_credentials";
    private static final String FILE = "luna_vault";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private final SharedPreferences prefs;

    public CredentialVault(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return generator.generateKey();
    }

    public void store(String name, String secret) throws Exception {
        Cipher cipher = Cipher.getInstance(TRANSFORM);
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(secret.getBytes("UTF-8"));
        prefs.edit()
            .putString(name + "_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
            .putString(name + "_data", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply();
    }

    public String read(String name) {
        String iv = prefs.getString(name + "_iv", "");
        String data = prefs.getString(name + "_data", "");
        if (iv.isEmpty() || data.isEmpty()) {
            return "";
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key(),
                new GCMParameterSpec(TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), "UTF-8");
        } catch (Exception error) {
            return "";
        }
    }

    public boolean has(String name) {
        return !prefs.getString(name + "_data", "").isEmpty();
    }

    public void clear(String name) {
        prefs.edit().remove(name + "_iv").remove(name + "_data").apply();
    }
}
