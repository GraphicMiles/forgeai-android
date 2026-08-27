package ai.luna.app;

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
public final class CredentialVault {

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
