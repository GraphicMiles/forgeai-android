package ai.luna.runtime;

import ai.luna.contracts.AgentDefinition;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every agent this runtime can run.
 *
 * <p>Small on purpose. The interesting question is not storage, it is that
 * there is now a plural: the app holds a list of agents, one of which happens
 * to be the one it shipped with.
 */
public final class AgentRegistry {

    private final Map<String, AgentDefinition> agents = new LinkedHashMap<>();
    private String defaultId = "";

    public AgentRegistry register(AgentDefinition agent) {
        if (agent == null || agent.id.isEmpty() || agents.containsKey(agent.id)) {
            return this;
        }
        agents.put(agent.id, agent);
        if (defaultId.isEmpty()) {
            defaultId = agent.id;
        }
        return this;
    }

    /** Installs an agent that arrived as JSON. Never built-in, whatever it says. */
    public AgentRegistry registerJson(JSONObject json) {
        if (json == null) {
            return this;
        }
        AgentDefinition agent = AgentDefinition.fromJson(json);
        if (agent.id.isEmpty() || agent.builtIn) {
            // Only the runtime decides what shipped with it.
            return this;
        }
        return register(agent);
    }

    public boolean has(String id) {
        return agents.containsKey(id);
    }

    public AgentDefinition get(String id) {
        return agents.get(id);
    }

    /** The agent to run when nothing has been chosen: Luna. */
    public AgentDefinition fallback() {
        return agents.get(defaultId);
    }

    public List<AgentDefinition> all() {
        return Collections.unmodifiableList(new ArrayList<>(agents.values()));
    }

    public JSONArray describe() {
        JSONArray out = new JSONArray();
        for (AgentDefinition agent : agents.values()) {
            try {
                out.put(agent.toJson());
            } catch (Exception ignored) {
                // One unprintable agent does not sink the list.
            }
        }
        return out;
    }
}
