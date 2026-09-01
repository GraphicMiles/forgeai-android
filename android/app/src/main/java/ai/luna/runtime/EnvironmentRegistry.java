package ai.luna.runtime;

import ai.luna.contracts.ExecutionProvider;
import ai.luna.contracts.ToolContext;
import ai.luna.contracts.ToolDefinition;
import ai.luna.contracts.Trace;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every place a tool could run, and which one is being used.
 *
 * <p>The phone is the first entry, not the only kind. A laptop on the same
 * network, a VPS over SSH and a Docker container are all the same shape of
 * thing: a platform name, a set of capabilities, somewhere to put files, maybe
 * a browser, and somewhere secrets live.
 *
 * <p>Declaring an environment is not the same as being able to reach it. Every
 * provider answers {@link ExecutionProvider#available()} and {@code problem()},
 * and this registry never routes work to something that says it is not there —
 * an offer that fails is worse than an offer that was never made.
 */
public final class EnvironmentRegistry {

    private final Map<String, ExecutionProvider> environments = new LinkedHashMap<>();
    private String activeId = "";

    public EnvironmentRegistry register(ExecutionProvider environment) {
        if (environment == null || environments.containsKey(environment.id())) {
            return this;
        }
        environments.put(environment.id(), environment);
        if (activeId.isEmpty()) {
            activeId = environment.id();
        }
        return this;
    }

    public boolean has(String id) {
        return environments.containsKey(id);
    }

    public ExecutionProvider get(String id) {
        return environments.get(id);
    }

    public List<ExecutionProvider> all() {
        return Collections.unmodifiableList(new ArrayList<>(environments.values()));
    }

    /** The one work runs in. The phone until somebody connects something. */
    public ExecutionProvider active() {
        ExecutionProvider environment = environments.get(activeId);
        return environment == null ? first() : environment;
    }

    public String activeId() {
        ExecutionProvider active = active();
        return active == null ? "" : active.id();
    }

    /**
     * Switches. Refused when the environment is unknown or unreachable, because
     * an agent stranded in an environment that is not there can do nothing at
     * all — including tell you why.
     */
    public boolean activate(String id) {
        ExecutionProvider environment = environments.get(id);
        if (environment == null || !environment.available()) {
            return false;
        }
        activeId = id;
        return true;
    }

    /** The context a tool call runs in, built from the active environment. */
    public ToolContext contextFor(String agentId, Trace trace) {
        ExecutionProvider environment = active();
        if (environment == null) {
            return new ToolContext(agentId, "core", null, null, null, trace, "android");
        }
        return new ToolContext(agentId, "core", environment.storage(), environment.browser(),
            environment.secrets(), environment.git(), trace, environment.platform());
    }

    /** Capabilities the active environment is prepared to offer. */
    public List<String> capabilities() {
        ExecutionProvider environment = active();
        return environment == null
            ? new ArrayList<String>() : environment.capabilities();
    }

    /**
     * Somewhere that could run this tool, or null.
     *
     * <p>This is what makes a second environment worth having: a tool needing a
     * shell is impossible on a phone and ordinary on a VPS, and the answer to
     * "can Luna do this?" becomes "not here, but on the machine you connected".
     */
    public ExecutionProvider where(ToolDefinition tool) {
        if (tool == null) {
            return null;
        }
        ExecutionProvider active = active();
        if (active != null && suits(active, tool)) {
            return active;
        }
        for (ExecutionProvider environment : environments.values()) {
            if (suits(environment, tool)) {
                return environment;
            }
        }
        return null;
    }

    /** The sentence to say when a tool cannot run here but could elsewhere. */
    public String elsewhere(ToolDefinition tool) {
        ExecutionProvider environment = where(tool);
        if (environment == null || environment.id().equals(activeId())) {
            return "";
        }
        return tool.id + " cannot run on this phone, but " + environment.displayName()
            + " could do it.";
    }

    private boolean suits(ExecutionProvider environment, ToolDefinition tool) {
        if (!environment.available() || !tool.runsOn(environment.platform())) {
            return false;
        }
        List<String> offered = environment.capabilities();
        for (String capability : tool.capabilities) {
            if (!offered.contains(capability)) {
                return false;
            }
        }
        return true;
    }

    private ExecutionProvider first() {
        for (ExecutionProvider environment : environments.values()) {
            return environment;
        }
        return null;
    }

    /** What a person is shown: where work can run, and what is wrong with it. */
    public JSONArray describe() {
        JSONArray out = new JSONArray();
        for (ExecutionProvider environment : environments.values()) {
            try {
                JSONObject row = new JSONObject();
                row.put("id", environment.id());
                row.put("name", environment.displayName());
                row.put("platform", environment.platform());
                row.put("local", environment.local());
                row.put("available", environment.available());
                row.put("problem", environment.problem() == null ? "" : environment.problem());
                row.put("active", environment.id().equals(activeId()));
                row.put("capabilities", new JSONArray(environment.capabilities()));
                out.put(row);
            } catch (Exception ignored) {
                // One unprintable environment does not sink the list.
            }
        }
        return out;
    }
}
