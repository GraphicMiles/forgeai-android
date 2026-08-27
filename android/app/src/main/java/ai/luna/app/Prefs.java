package ai.luna.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Everything Luna remembers between launches: how she is allowed to act, where
 * the granted folder is, which model is loaded, and any cloud keys.
 */
public final class Prefs {

    public static final String MODE_ASK = "ask";
    public static final String MODE_AUTO = "auto";

    private static final String FILE = "luna_prefs";
    private static final String KEY_MODE = "execution_mode";
    private static final String KEY_WORKSPACE = "workspace_uri";
    private static final String KEY_ACTIVE_MODEL = "active_model";
    private static final String KEY_ENDPOINT = "ollama_endpoint";
    private static final String KEY_CLOUD = "cloud_providers";
    private static final String KEY_FAILOVER = "cloud_failover";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String executionMode() {
        String value = prefs.getString(KEY_MODE, MODE_ASK);
        return MODE_AUTO.equals(value) ? MODE_AUTO : MODE_ASK;
    }

    public void setExecutionMode(String mode) {
        prefs.edit().putString(KEY_MODE, MODE_AUTO.equals(mode) ? MODE_AUTO : MODE_ASK).apply();
    }

    public boolean unattended() {
        return MODE_AUTO.equals(executionMode());
    }

    public String workspaceUri() {
        return prefs.getString(KEY_WORKSPACE, "");
    }

    public void setWorkspaceUri(String uri) {
        prefs.edit().putString(KEY_WORKSPACE, uri == null ? "" : uri).apply();
    }

    public String activeModelId() {
        return prefs.getString(KEY_ACTIVE_MODEL, "");
    }

    public void setActiveModelId(String id) {
        prefs.edit().putString(KEY_ACTIVE_MODEL, id == null ? "" : id).apply();
    }

    public String endpoint() {
        return prefs.getString(KEY_ENDPOINT, "");
    }

    public void setEndpoint(String endpoint) {
        prefs.edit().putString(KEY_ENDPOINT, endpoint == null ? "" : endpoint).apply();
    }

    public boolean failoverEnabled() {
        return prefs.getBoolean(KEY_FAILOVER, false);
    }

    public void setFailoverEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FAILOVER, enabled).apply();
    }

    public JSONArray cloudProviders() {
        try {
            return new JSONArray(prefs.getString(KEY_CLOUD, "[]"));
        } catch (JSONException error) {
            return new JSONArray();
        }
    }

    public void addCloudProvider(JSONObject provider) {
        JSONArray next = cloudProviders();
        next.put(provider);
        prefs.edit().putString(KEY_CLOUD, next.toString()).apply();
    }

    public void removeCloudProvider(String id) {
        JSONArray current = cloudProviders();
        JSONArray next = new JSONArray();
        for (int index = 0; index < current.length(); index++) {
            JSONObject item = current.optJSONObject(index);
            if (item != null && !item.optString("id").equals(id)) {
                next.put(item);
            }
        }
        prefs.edit().putString(KEY_CLOUD, next.toString()).apply();
    }

    public JSONObject cloudProvider(String id) {
        JSONArray all = cloudProviders();
        for (int index = 0; index < all.length(); index++) {
            JSONObject item = all.optJSONObject(index);
            if (item != null && item.optString("id").equals(id)) {
                return item;
            }
        }
        return null;
    }

    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
