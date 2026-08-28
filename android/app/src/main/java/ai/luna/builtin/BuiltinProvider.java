package ai.luna.builtin;

import ai.luna.app.Tools;
import ai.luna.contracts.ToolContext;
import ai.luna.contracts.ToolDefinition;
import ai.luna.contracts.ToolProvider;
import ai.luna.contracts.ToolResult;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the four built-in providers have in common.
 *
 * <p>They describe their tools as data and then hand the actual work to
 * {@link Tools}, which is unchanged. The point of the split is not new
 * behaviour: it is that the runtime now learns the tool list by asking, so a
 * plugin providing {@code github.pr.create} arrives the same way
 * {@code core.github} does.
 */
public abstract class BuiltinProvider implements ToolProvider {

    private final List<ToolDefinition> definitions;
    private final Map<String, ToolDefinition> byId = new HashMap<>();

    protected BuiltinProvider(List<ToolDefinition> declared) {
        List<ToolDefinition> owned = new ArrayList<>(declared.size());
        for (ToolDefinition definition : declared) {
            ToolDefinition credited = definition.from(id());
            owned.add(credited);
            byId.put(credited.id, credited);
        }
        this.definitions = Collections.unmodifiableList(owned);
    }

    @Override
    public final List<ToolDefinition> definitions() {
        return definitions;
    }

    @Override
    public final boolean owns(String toolId) {
        return byId.containsKey(toolId);
    }

    public final ToolDefinition definition(String toolId) {
        return byId.get(toolId);
    }

    @Override
    public ToolResult run(ToolContext context, String toolId, JSONObject args) {
        ToolDefinition definition = byId.get(toolId);
        if (definition == null) {
            return ToolResult.failed("There is no tool called " + toolId + ".");
        }
        String missing = missingResource(definition, context);
        if (missing != null) {
            return ToolResult.failed(missing);
        }
        long started = System.currentTimeMillis();
        Tools.Env env = new Tools.Env(context.storage, context.browser, context.secrets,
            context.trace);
        String observation = Tools.run(env, toolId, args == null ? new JSONObject() : args);
        long took = System.currentTimeMillis() - started;
        if (observation != null && observation.startsWith("Failed: ")) {
            return ToolResult.failed(observation.substring("Failed: ".length())).withTiming(took);
        }
        return ToolResult.ok(observation, took);
    }

    /** Why this call cannot even be attempted here, or null. */
    protected String missingResource(ToolDefinition definition, ToolContext context) {
        if (definition.requires.contains("workspace") && !context.hasStorage()) {
            return "No folder is granted, so there is nothing to read or write.";
        }
        if (definition.requires.contains("browser") && !context.hasBrowser()) {
            return "There is no browser available on this device.";
        }
        return null;
    }
}
