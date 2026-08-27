package ai.luna.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Everything Luna remembers between launches: how she is allowed to act, which
 * folders she has been given, which model is loaded, and which providers exist.
 *
 * One thing is deliberately absent — secrets. A provider row holds a label, an
 * address and a model name. The key itself lives in {@link CredentialVault},
 * under the provider's id.
 */
public final class Prefs {

    public static final String MODE_ASK = "ask";
    public static final String MODE_AUTO = "auto";

    public static final String RULE_ASK = "ask";
    public static final String RULE_ALWAYS = "always";
    public static final String RULE_NEVER = "never";

    private static final String FILE = "luna_prefs";
    private static final String KEY_MODE = "execution_mode";
    private static final String KEY_WORKSPACE = "workspace_uri";
    private static final String KEY_GRANTS = "workspace_grants";
    private static final String KEY_ACTIVE_MODEL = "active_model";
    private static final String KEY_ENDPOINT = "ollama_endpoint";
    private static final String KEY_CLOUD = "cloud_providers";
    private static final String KEY_FAILOVER = "cloud_failover";
    private static final String KEY_TOOL_RULES = "tool_rules";
    private static final String KEY_WIFI_ONLY = "downloads_wifi_only";
    private static final String KEY_BATTERY_GUARD = "downloads_battery_guard";
    private static final String KEY_KEEP_WARM = "keep_model_warm";
    private static final String KEY_THEME = "theme";
    private static final String KEY_TEXT_SCALE = "text_scale";
    private static final String KEY_IMPORTED = "imported_models";
    private static final String KEY_DOWNLOADS = "download_state";
    private static final String KEY_CHATS = "chat_index";
    private static final String KEY_ACTIVE_CHAT = "active_chat";
    private static final String KEY_WALKTHROUGH = "walkthrough_done";
    private static final String KEY_BUDGET_STEPS = "budget_steps";
    private static final String KEY_BUDGET_SECONDS = "budget_seconds";
    private static final String KEY_BUDGET_CLOUD = "budget_cloud_calls";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // --- how she is allowed to act -------------------------------------------

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

    /** ask, always or never, per tool. A missing rule means ask. */
    public String toolRule(String tool) {
        try {
            JSONObject rules = new JSONObject(prefs.getString(KEY_TOOL_RULES, "{}"));
            String value = rules.optString(tool, RULE_ASK);
            if (RULE_ALWAYS.equals(value) || RULE_NEVER.equals(value)) {
                return value;
            }
        } catch (JSONException ignored) {
            // A broken rule set falls back to asking, which is the safe end.
        }
        return RULE_ASK;
    }

    public void setToolRule(String tool, String rule) {
        try {
            JSONObject rules = new JSONObject(prefs.getString(KEY_TOOL_RULES, "{}"));
            if (RULE_ASK.equals(rule)) {
                rules.remove(tool);
            } else {
                rules.put(tool, RULE_ALWAYS.equals(rule) ? RULE_ALWAYS : RULE_NEVER);
            }
            prefs.edit().putString(KEY_TOOL_RULES, rules.toString()).apply();
        } catch (JSONException ignored) {
            // Leaving the rule unchanged is safer than writing a broken one.
        }
    }

    public JSONObject toolRules() {
        try {
            return new JSONObject(prefs.getString(KEY_TOOL_RULES, "{}"));
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

    public int budgetSteps() {
        return prefs.getInt(KEY_BUDGET_STEPS, 12);
    }

    public int budgetSeconds() {
        return prefs.getInt(KEY_BUDGET_SECONDS, 300);
    }

    public int budgetCloudCalls() {
        return prefs.getInt(KEY_BUDGET_CLOUD, 8);
    }

    public void setBudget(int steps, int seconds, int cloudCalls) {
        prefs.edit()
            .putInt(KEY_BUDGET_STEPS, Math.max(1, steps))
            .putInt(KEY_BUDGET_SECONDS, Math.max(15, seconds))
            .putInt(KEY_BUDGET_CLOUD, Math.max(1, cloudCalls))
            .apply();
    }

    // --- folders --------------------------------------------------------------

    public String workspaceUri() {
        return prefs.getString(KEY_WORKSPACE, "");
    }

    public void setWorkspaceUri(String uri) {
        prefs.edit().putString(KEY_WORKSPACE, uri == null ? "" : uri).apply();
    }

    /** Every folder that has ever been granted, so you can switch back to one. */
    public JSONArray grants() {
        try {
            return new JSONArray(prefs.getString(KEY_GRANTS, "[]"));
        } catch (JSONException error) {
            return new JSONArray();
        }
    }

    public void rememberGrant(String uri, String name) {
        try {
            JSONArray current = grants();
            for (int index = 0; index < current.length(); index++) {
                JSONObject item = current.optJSONObject(index);
                if (item != null && item.optString("uri").equals(uri)) {
                    item.put("name", name);
                    prefs.edit().putString(KEY_GRANTS, current.toString()).apply();
                    return;
                }
            }
            JSONObject entry = new JSONObject();
            entry.put("uri", uri);
            entry.put("name", name);
            entry.put("at", System.currentTimeMillis());
            current.put(entry);
            prefs.edit().putString(KEY_GRANTS, current.toString()).apply();
        } catch (JSONException ignored) {
            // The grant still works this session; only the shortcut is lost.
        }
    }

    public void forgetGrant(String uri) {
        JSONArray current = grants();
        JSONArray next = new JSONArray();
        for (int index = 0; index < current.length(); index++) {
            JSONObject item = current.optJSONObject(index);
            if (item != null && !item.optString("uri").equals(uri)) {
                next.put(item);
            }
        }
        prefs.edit().putString(KEY_GRANTS, next.toString()).apply();
    }

    // --- models ---------------------------------------------------------------

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

    /** Models you brought yourself. No publisher, so no checksum to check against. */
    public JSONArray importedModels() {
        try {
            return new JSONArray(prefs.getString(KEY_IMPORTED, "[]"));
        } catch (JSONException error) {
            return new JSONArray();
        }
    }

    public void addImportedModel(JSONObject model) {
        JSONArray next = importedModels();
        next.put(model);
        prefs.edit().putString(KEY_IMPORTED, next.toString()).apply();
    }

    public void removeImportedModel(String id) {
        JSONArray current = importedModels();
        JSONArray next = new JSONArray();
        for (int index = 0; index < current.length(); index++) {
            JSONObject item = current.optJSONObject(index);
            if (item != null && !item.optString("id").equals(id)) {
                next.put(item);
            }
        }
        prefs.edit().putString(KEY_IMPORTED, next.toString()).apply();
    }

    public JSONObject importedModel(String id) {
        JSONArray all = importedModels();
        for (int index = 0; index < all.length(); index++) {
            JSONObject item = all.optJSONObject(index);
            if (item != null && item.optString("id").equals(id)) {
                return item;
            }
        }
        return null;
    }

    // --- downloads ------------------------------------------------------------

    public boolean wifiOnly() {
        return prefs.getBoolean(KEY_WIFI_ONLY, false);
    }

    public void setWifiOnly(boolean value) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply();
    }

    public boolean batteryGuard() {
        return prefs.getBoolean(KEY_BATTERY_GUARD, true);
    }

    public void setBatteryGuard(boolean value) {
        prefs.edit().putBoolean(KEY_BATTERY_GUARD, value).apply();
    }

    /** What each download was doing when we last looked. Survives a kill. */
    public JSONObject downloadState() {
        try {
            return new JSONObject(prefs.getString(KEY_DOWNLOADS, "{}"));
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

    /**
     * Called once at startup. A download recorded as running cannot be running
     * — the process it belonged to is gone — so it is marked paused, which is
     * the state the Resume button knows how to act on. The .part file on disk
     * still holds the bytes.
     */
    public void settleDownloadsAfterRestart() {
        try {
            JSONObject all = downloadState();
            boolean changed = false;
            java.util.Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                JSONObject entry = all.optJSONObject(id);
                if (entry == null) {
                    continue;
                }
                String state = entry.optString("state");
                if (state.equals("downloading") || state.equals("verifying") || state.equals("waiting")) {
                    entry.put("state", "paused");
                    entry.put("detail", "Stopped when Luna closed. Resume picks up where it left off.");
                    changed = true;
                }
            }
            if (changed) {
                prefs.edit().putString(KEY_DOWNLOADS, all.toString()).apply();
            }
        } catch (JSONException ignored) {
            // The .part file is the real record.
        }
    }

    public void setDownloadState(String id, String state, long completed, long total) {
        try {
            JSONObject all = downloadState();
            if (state == null) {
                all.remove(id);
            } else {
                JSONObject entry = new JSONObject();
                entry.put("state", state);
                entry.put("completed", completed);
                entry.put("total", total);
                entry.put("at", System.currentTimeMillis());
                all.put(id, entry);
            }
            prefs.edit().putString(KEY_DOWNLOADS, all.toString()).apply();
        } catch (JSONException ignored) {
            // The part file on disk is the real record; this is only the label.
        }
    }

    // --- the model in memory ---------------------------------------------------

    public boolean keepWarm() {
        return prefs.getBoolean(KEY_KEEP_WARM, true);
    }

    public void setKeepWarm(boolean value) {
        prefs.edit().putBoolean(KEY_KEEP_WARM, value).apply();
    }

    // --- look and feel ----------------------------------------------------------

    public String theme() {
        return prefs.getString(KEY_THEME, "system");
    }

    public void setTheme(String theme) {
        prefs.edit().putString(KEY_THEME, theme == null ? "system" : theme).apply();
    }

    public float textScale() {
        return prefs.getFloat(KEY_TEXT_SCALE, 1.0f);
    }

    public void setTextScale(float scale) {
        prefs.edit().putFloat(KEY_TEXT_SCALE, Math.max(0.85f, Math.min(1.5f, scale))).apply();
    }

    public boolean walkthroughDone() {
        return prefs.getBoolean(KEY_WALKTHROUGH, false);
    }

    public void setWalkthroughDone(boolean done) {
        prefs.edit().putBoolean(KEY_WALKTHROUGH, done).apply();
    }

    // --- chats ------------------------------------------------------------------

    public JSONArray chats() {
        try {
            return new JSONArray(prefs.getString(KEY_CHATS, "[]"));
        } catch (JSONException error) {
            return new JSONArray();
        }
    }

    public void setChats(JSONArray chats) {
        prefs.edit().putString(KEY_CHATS, chats.toString()).apply();
    }

    public String activeChatId() {
        return prefs.getString(KEY_ACTIVE_CHAT, "");
    }

    public void setActiveChatId(String id) {
        prefs.edit().putString(KEY_ACTIVE_CHAT, id == null ? "" : id).apply();
    }

    // --- providers ---------------------------------------------------------------

    /**
     * Provider rows, without keys. Any row still carrying an "apiKey" from an
     * older build is moved into the vault here and stripped from disk, so the
     * upgrade cleans up after the mistake instead of leaving it lying around.
     */
    public JSONArray cloudProviders(CredentialVault vault) {
        JSONArray all = raw();
        boolean rewrite = false;
        for (int index = 0; index < all.length(); index++) {
            JSONObject item = all.optJSONObject(index);
            if (item == null || !item.has("apiKey")) {
                continue;
            }
            String key = item.optString("apiKey", "");
            item.remove("apiKey");
            rewrite = true;
            if (!key.isEmpty() && vault != null) {
                try {
                    vault.store("cloud:" + item.optString("id"), key);
                } catch (Exception ignored) {
                    // If the keystore will not take it, the key is dropped rather
                    // than written back to plain storage.
                }
            }
        }
        if (rewrite) {
            prefs.edit().putString(KEY_CLOUD, all.toString()).apply();
        }
        return all;
    }

    private JSONArray raw() {
        try {
            return new JSONArray(prefs.getString(KEY_CLOUD, "[]"));
        } catch (JSONException error) {
            return new JSONArray();
        }
    }

    public void addCloudProvider(JSONObject provider) {
        JSONArray next = raw();
        provider.remove("apiKey");
        next.put(provider);
        prefs.edit().putString(KEY_CLOUD, next.toString()).apply();
    }

    public void updateCloudProvider(String id, String label, String model) {
        try {
            JSONArray all = raw();
            for (int index = 0; index < all.length(); index++) {
                JSONObject item = all.optJSONObject(index);
                if (item != null && item.optString("id").equals(id)) {
                    if (label != null && !label.isEmpty()) {
                        item.put("label", label);
                    }
                    if (model != null && !model.isEmpty()) {
                        item.put("model", model);
                    }
                    item.put("checkedAt", System.currentTimeMillis());
                }
            }
            prefs.edit().putString(KEY_CLOUD, all.toString()).apply();
        } catch (JSONException ignored) {
            // Nothing changes if the row will not serialise.
        }
    }

    public void removeCloudProvider(String id) {
        JSONArray current = raw();
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
        JSONArray all = raw();
        for (int index = 0; index < all.length(); index++) {
            JSONObject item = all.optJSONObject(index);
            if (item != null && item.optString("id").equals(id)) {
                return item;
            }
        }
        return null;
    }

    // --- everything ---------------------------------------------------------------

    /** Settings only. Files, downloaded models and grants are left alone. */
    public void clearAll() {
        JSONArray keepGrants = grants();
        JSONArray keepImported = importedModels();
        prefs.edit().clear().apply();
        prefs.edit()
            .putString(KEY_GRANTS, keepGrants.toString())
            .putString(KEY_IMPORTED, keepImported.toString())
            .apply();
    }

    /** A copy of the settings, for the backup file. Secrets are not included. */
    public JSONObject exportSettings() throws JSONException {
        JSONObject out = new JSONObject();
        out.put("executionMode", executionMode());
        out.put("toolRules", toolRules());
        out.put("endpoint", endpoint());
        out.put("failover", failoverEnabled());
        out.put("wifiOnly", wifiOnly());
        out.put("batteryGuard", batteryGuard());
        out.put("keepWarm", keepWarm());
        out.put("theme", theme());
        out.put("textScale", textScale());
        out.put("budgetSteps", budgetSteps());
        out.put("budgetSeconds", budgetSeconds());
        out.put("budgetCloudCalls", budgetCloudCalls());
        out.put("providers", cloudProviders(null));
        out.put("note", "Keys are not in this file. They stay in the keystore on the phone that made it.");
        return out;
    }

    public void importSettings(JSONObject saved) {
        if (saved == null) {
            return;
        }
        setExecutionMode(saved.optString("executionMode", executionMode()));
        setEndpoint(saved.optString("endpoint", endpoint()));
        setFailoverEnabled(saved.optBoolean("failover", failoverEnabled()));
        setWifiOnly(saved.optBoolean("wifiOnly", wifiOnly()));
        setBatteryGuard(saved.optBoolean("batteryGuard", batteryGuard()));
        setKeepWarm(saved.optBoolean("keepWarm", keepWarm()));
        setTheme(saved.optString("theme", theme()));
        setTextScale((float) saved.optDouble("textScale", textScale()));
        setBudget(saved.optInt("budgetSteps", budgetSteps()),
            saved.optInt("budgetSeconds", budgetSeconds()),
            saved.optInt("budgetCloudCalls", budgetCloudCalls()));
        JSONObject rules = saved.optJSONObject("toolRules");
        if (rules != null) {
            prefs.edit().putString(KEY_TOOL_RULES, rules.toString()).apply();
        }
    }
}
