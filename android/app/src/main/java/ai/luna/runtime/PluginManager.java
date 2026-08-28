package ai.luna.runtime;

import ai.luna.contracts.PluginManifest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Installing, removing and loading plugins.
 *
 * <p>A plugin's contents go into the same registries the built-ins use, so
 * there is exactly one code path for knowledge and exactly one for agents. A
 * plugin's skill is resolved by the same resolver, narrowed by the same agent,
 * and shown in the same list — it is simply credited to somebody else.
 *
 * <p>Nothing is executed. That is the whole design: the worst an installed
 * plugin can do is tell the model something, and the model can only act through
 * tools the environment already allowed.
 */
public final class PluginManager {

    /** Where installed manifests live between runs. */
    public interface Store {
        JSONArray load();

        void save(JSONArray manifests);
    }

    private final PluginRegistry plugins = new PluginRegistry();
    private final PluginVerifier verifier;
    private final SkillRegistry skills;
    private final AgentRegistry agents;
    private final WorkflowRegistry workflows;
    private final Store store;

    public PluginManager(PluginVerifier verifier, SkillRegistry skills, AgentRegistry agents,
                         Store store) {
        this(verifier, skills, agents, null, store);
    }

    public PluginManager(PluginVerifier verifier, SkillRegistry skills, AgentRegistry agents,
                         WorkflowRegistry workflows, Store store) {
        this.verifier = verifier == null ? new PluginVerifier() : verifier;
        this.skills = skills;
        this.agents = agents;
        this.workflows = workflows;
        this.store = store;
    }

    public PluginRegistry registry() {
        return plugins;
    }

    /** Reinstates everything installed previously, verifying it all again. */
    public List<String> restore() {
        List<String> refused = new ArrayList<>();
        if (store == null) {
            return refused;
        }
        JSONArray saved = store.load();
        if (saved == null) {
            return refused;
        }
        for (int index = 0; index < saved.length(); index++) {
            JSONObject json = saved.optJSONObject(index);
            if (json == null) {
                continue;
            }
            // Verified on the way back in, not just on the way out: what a
            // device trusts can change between one run and the next.
            String problem = install(json, false);
            if (problem != null) {
                refused.add(json.optString("id", "?") + ": " + problem);
            }
        }
        persist();
        return refused;
    }

    /** Installs one. Returns null when it worked, or the reason it did not. */
    public String install(JSONObject json) {
        String problem = install(json, true);
        if (problem == null) {
            persist();
        }
        return problem;
    }

    private String install(JSONObject json, boolean fresh) {
        PluginManifest manifest = PluginManifest.fromJson(json);
        String refusal = verifier.refuse(manifest);
        if (refusal != null) {
            return refusal;
        }
        if (fresh && plugins.has(manifest.id)) {
            return manifest.name + " is already installed.";
        }
        // Content first, registry second: a plugin only counts as installed
        // once everything it carries has been accepted.
        for (JSONObject skill : manifest.skills) {
            skills.addJson(skill, manifest.id);
        }
        for (JSONObject agent : manifest.agents) {
            agents.registerJson(agent);
        }
        for (JSONObject workflow : manifest.workflows) {
            if (workflows != null) {
                workflows.register(workflow);
            }
        }
        plugins.put(manifest);
        return null;
    }

    /**
     * Removes one.
     *
     * <p>Its skills and agents are already loaded into registries that do not
     * support removal, so the honest thing to say is that this takes effect
     * when the runtime next starts — and that is what the caller is told.
     */
    public boolean remove(String id) {
        if (!plugins.has(id)) {
            return false;
        }
        plugins.drop(id);
        persist();
        return true;
    }

    public void setEnabled(String id, boolean enabled) {
        plugins.setEnabled(id, enabled);
        persist();
    }

    public JSONArray describe() {
        return plugins.describe();
    }

    private void persist() {
        if (store == null) {
            return;
        }
        JSONArray out = new JSONArray();
        for (PluginManifest manifest : plugins.all()) {
            try {
                out.put(manifest.toJson());
            } catch (Exception ignored) {
                // A manifest that cannot be written is a manifest that is lost
                // on restart, which is better than losing the others with it.
            }
        }
        store.save(out);
    }
}
