package ai.luna.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@CapacitorPlugin(name = "CredentialVault")
public class CredentialVault extends Plugin {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "luna-github-token";
    private static final String PREFS = "luna-secure-vault";
    private static final String TOKEN = "github-token";

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE); store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build());
        return generator.generateKey();
    }

    static void storeToken(Context context, String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "." + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(TOKEN, value).apply();
    }

    static String getToken(Context context) throws Exception {
        String value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TOKEN, "");
        if (value.isEmpty()) return "";
        String[] parts = value.split("\\.", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    @PluginMethod
    public void storeGithubToken(PluginCall call) {
        String token = call.getString("token", "").trim();
        if (!(token.startsWith("ghp_") || token.startsWith("github_pat_")) || token.length() < 30) { call.reject("A valid GitHub PAT is required."); return; }
        try { storeToken(getContext(), token); JSObject result = new JSObject(); result.put("stored", true); call.resolve(result); }
        catch (Exception error) { call.reject("Unable to encrypt GitHub token."); }
    }

    @PluginMethod
    public void hasGithubToken(PluginCall call) {
        try { JSObject result = new JSObject(); result.put("stored", !getToken(getContext()).isEmpty()); call.resolve(result); }
        catch (Exception error) { call.reject("Unable to inspect credential vault."); }
    }

    @PluginMethod
    public void clearGithubToken(PluginCall call) {
        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(TOKEN).apply();
        call.resolve();
    }
}
