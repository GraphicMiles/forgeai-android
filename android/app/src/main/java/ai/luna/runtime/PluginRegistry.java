package ai.luna.runtime;

import ai.luna.contracts.PluginManifest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What is installed, and what is switched on. */
public final class PluginRegistry {

    private final Map<String, PluginManifest> installed = new LinkedHashMap<>();
    private final Set<String> disabled = new HashSet<>();

    public boolean has(String id) {
        return installed.containsKey(id);
    }

    public PluginManifest get(String id) {
        return installed.get(id);
    }

    void put(PluginManifest manifest) {
        installed.put(manifest.id, manifest);
    }

    void drop(String id) {
        installed.remove(id);
        disabled.remove(id);
    }

    public boolean isEnabled(String id) {
        return installed.containsKey(id) && !disabled.contains(id);
    }

    public void setEnabled(String id, boolean enabled) {
        if (!installed.containsKey(id)) {
            return;
        }
        if (enabled) {
            disabled.remove(id);
        } else {
            disabled.add(id);
        }
    }

    public List<PluginManifest> all() {
        return Collections.unmodifiableList(new ArrayList<>(installed.values()));
    }

    public List<String> disabledIds() {
        return Collections.unmodifiableList(new ArrayList<>(disabled));
    }

    /** What a person is shown: who wrote it, what it brought, is it signed. */
    public JSONArray describe() {
        JSONArray out = new JSONArray();
        for (PluginManifest manifest : installed.values()) {
            try {
                JSONObject row = new JSONObject();
                row.put("id", manifest.id);
                row.put("name", manifest.name);
                row.put("version", manifest.version);
                row.put("author", manifest.author);
                row.put("description", manifest.description);
                row.put("capabilities", new JSONArray(manifest.capabilities));
                row.put("skills", manifest.skills.size());
                row.put("agents", manifest.agents.size());
                row.put("workflows", manifest.workflows.size());
                row.put("signed", manifest.signed());
                row.put("enabled", isEnabled(manifest.id));
                out.put(row);
            } catch (Exception ignored) {
                // One unprintable plugin does not sink the list.
            }
        }
        return out;
    }
}
