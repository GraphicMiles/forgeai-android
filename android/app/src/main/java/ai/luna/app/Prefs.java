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

    private static final String KEY_SKILLS_OFF = "skills_off";
    private static final String KEY_AGENT = "active_agent";
    private static final String KEY_AGENTS = "installed_agents";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
        dropOldToolRules();
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

    /** Which agent is running. Luna until somebody installs another. */
    public String activeAgentId() {
        return prefs.getString(KEY_AGENT, "luna");
    }

    public void setActiveAgentId(String id) {
        prefs.edit().putString(KEY_AGENT, id == null || id.isEmpty() ? "luna" : id).apply();
    }

    /**
     * Agents installed alongside Luna, as the JSON they arrived as.
     *
     * <p>Stored verbatim rather than parsed into columns: an agent definition
     * is a document, and a store that understands its fields would have to be
     * migrated every time one is added.
     */
    public java.util.List<org.json.JSONObject> installedAgents() {
        java.util.List<org.json.JSONObject> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray array = new org.json.JSONArray(prefs.getString(KEY_AGENTS, "[]"));
            for (int index = 0; index < array.length(); index++) {
                org.json.JSONObject row = array.optJSONObject(index);
                if (row != null) {
                    out.add(row);
                }
            }
        } catch (Exception ignored) {
            // A corrupt list means no installed agents, never a crash.
        }
        return out;
    }

    public void setInstalledAgents(org.json.JSONArray agents) {
        prefs.edit().putString(KEY_AGENTS, agents == null ? "[]" : agents.toString()).apply();
    }

    /**
     * Skills the person has switched off.
     *
     * <p>Stored as one comma-separated string: this is a short list of ids, and
     * a set of them does not deserve its own store.
     */
    public java.util.List<String> disabledSkills() {
        String raw = prefs.getString(KEY_SKILLS_OFF, "");
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : raw.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }

    public void setDisabledSkills(java.util.List<String> ids) {
        StringBuilder joined = new StringBuilder();
        if (ids != null) {
            for (String id : ids) {
                if (id == null || id.trim().isEmpty()) {
                    continue;
                }
                if (joined.length() > 0) {
                    joined.append(',');
                }
                joined.append(id.trim());
            }
        }
        prefs.edit().putString(KEY_SKILLS_OFF, joined.toString()).apply();
    }

    /**
     * Per-tool rules used to live here. They are gone on purpose: one switch is
     * the whole gate, and a stored rule from an older build must not quietly
     * keep granting permission. The key is cleared the first time this runs.
     */
    private void dropOldToolRules() {
        if (prefs.contains(KEY_TOOL_RULES)) {
            prefs.edit().remove(KEY_TOOL_RULES).apply();
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
                return withDefaults(item);
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
        for (int index = 0; index < all.length(); index++) {
            withDefaults(all.optJSONObject(index));
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

    /**
     * Fills in the fields a row from an older build does not have. Every
     * provider stored before Luna spoke three shapes was OpenAI-shaped, which
     * is exactly what the defaults say, so the upgrade is silent.
     */
    public static JSONObject withDefaults(JSONObject row) {
        if (row == null) {
            return null;
        }
        try {
            String kind = CloudProvider.normaliseKind(row.optString("kind", ""));
            row.put("kind", kind);
            String style = row.optString("authStyle", "");
            if (style.isEmpty()) {
                style = CloudProvider.defaultAuthStyle(kind);
            }
            row.put("authStyle", style);
            if (row.optString("authName", "").isEmpty()) {
                row.put("authName", CloudProvider.defaultAuthName(kind, style));
            }
            if (row.optJSONObject("headers") == null) {
                row.put("headers", new JSONObject());
            }
        } catch (JSONException ignored) {
            // The row is still usable; the defaults resolve again at call time.
        }
        return row;
    }

    public void addCloudProvider(JSONObject provider) {
        JSONArray next = raw();
        provider.remove("apiKey");
        next.put(provider);
        prefs.edit().putString(KEY_CLOUD, next.toString()).apply();
    }

    public void updateCloudProvider(String id, String label, String model) {
        updateCloudProvider(id, label, model, null, null, null, null, null);
    }

    /**
     * Change any part of a provider. Every argument is optional: null means
     * "leave this as it was", so the model picker can set a model without
     * knowing anything about headers.
     */
    public void updateCloudProvider(String id, String label, String model, String kind,
                                    String baseUrl, String authStyle, String authName,
                                    JSONObject headers) {
        try {
            JSONArray all = raw();
            for (int index = 0; index < all.length(); index++) {
                JSONObject item = all.optJSONObject(index);
                if (item == null || !item.optString("id").equals(id)) {
                    continue;
                }
                if (label != null && !label.isEmpty()) {
                    item.put("label", label);
                }
                if (model != null && !model.isEmpty()) {
                    item.put("model", model);
                }
                if (kind != null && !kind.isEmpty()) {
                    item.put("kind", CloudProvider.normaliseKind(kind));
                }
                if (baseUrl != null && !baseUrl.isEmpty()) {
                    item.put("baseUrl", EndpointPolicy.tidy(baseUrl));
                }
                if (authStyle != null && !authStyle.isEmpty()) {
                    item.put("authStyle", authStyle);
                }
                if (authName != null && !authName.isEmpty()) {
                    item.put("authName", authName);
                }
                if (headers != null) {
                    item.put("headers", headers);
                }
                item.put("checkedAt", System.currentTimeMillis());
                withDefaults(item);
            }
            prefs.edit().putString(KEY_CLOUD, all.toString()).apply();
        } catch (JSONException ignored) {
            // Nothing changes if the row will not serialise.
        }
    }

    /**
     * Forget which model this provider was using. Called when the provider
     * says the model is gone: leaving a dead id in place means every future
     * job fails the same way, with the same 404, until somebody notices.
     */
    public void clearCloudModel(String id) {
        try {
            JSONArray all = raw();
            for (int index = 0; index < all.length(); index++) {
                JSONObject item = all.optJSONObject(index);
                if (item != null && item.optString("id").equals(id)) {
                    item.put("model", "");
                }
            }
            prefs.edit().putString(KEY_CLOUD, all.toString()).apply();
        } catch (JSONException ignored) {
            // The next run will report the same thing, which is no worse.
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
                return withDefaults(item);
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
    }
}
