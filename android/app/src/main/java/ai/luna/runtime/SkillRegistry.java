package ai.luna.runtime;

import ai.luna.contracts.SkillDefinition;
import ai.luna.contracts.SkillProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the runtime knows how to teach an agent.
 *
 * <p>Skills are data, so this registry is mostly a careful list: one owner per
 * id, a way to turn one off, and a way to read them all back as JSON for a UI
 * that will eventually let a person see exactly what their agent has been told.
 */
public final class SkillRegistry {

    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();
    private final Set<String> disabled = new HashSet<>();

    public SkillRegistry register(SkillProvider provider) {
        if (provider == null) {
            return this;
        }
        for (SkillDefinition skill : provider.skills()) {
            add(skill.from(provider.id()));
        }
        return this;
    }

    /** First claim on an id wins, exactly as with tools. */
    public SkillRegistry add(SkillDefinition skill) {
        if (skill != null && !skill.id.isEmpty() && !skills.containsKey(skill.id)) {
            skills.put(skill.id, skill);
        }
        return this;
    }

    /** Installs a skill that arrived as JSON, which is how plugins ship them. */
    public SkillRegistry addJson(JSONObject json, String provider) {
        if (json == null) {
            return this;
        }
        SkillDefinition skill = SkillDefinition.fromJson(json);
        if (skill.id.isEmpty() || skill.instructions.isEmpty()) {
            return this;
        }
        return add(skill.from(provider));
    }

    public SkillRegistry disable(Collection<String> ids) {
        disabled.clear();
        if (ids != null) {
            disabled.addAll(ids);
        }
        return this;
    }

    public boolean has(String id) {
        return skills.containsKey(id);
    }

    public SkillDefinition get(String id) {
        return skills.get(id);
    }

    public boolean isEnabled(String id) {
        return skills.containsKey(id) && !disabled.contains(id);
    }

    /** Everything registered, including anything switched off. */
    public List<SkillDefinition> all() {
        return Collections.unmodifiableList(new ArrayList<>(skills.values()));
    }

    /** Everything still switched on. */
    public List<SkillDefinition> enabled() {
        List<SkillDefinition> out = new ArrayList<>();
        for (SkillDefinition skill : skills.values()) {
            if (!disabled.contains(skill.id)) {
                out.add(skill);
            }
        }
        return out;
    }

    public JSONArray describe() {
        JSONArray out = new JSONArray();
        for (SkillDefinition skill : skills.values()) {
            try {
                JSONObject json = skill.toJson();
                json.put("enabled", !disabled.contains(skill.id));
                out.put(json);
            } catch (Exception ignored) {
                // One unprintable skill does not sink the list.
            }
        }
        return out;
    }
}
