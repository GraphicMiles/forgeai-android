package ai.luna.contracts;

import org.json.JSONObject;

import java.util.List;

/**
 * Something that offers tools.
 *
 * <p>The core does not care whether a tool came from Luna itself, from a plugin
 * a user installed, from a remote server or from a sub-agent standing in as a
 * tool. They all arrive through this interface, they all describe themselves
 * with {@link ToolDefinition}, and they all answer with {@link ToolResult}.
 *
 * <p>Implementations must not throw. A tool that fails returns a failure.
 */
public interface ToolProvider {

    /** {@code core.filesystem}, {@code github}, {@code acme.deploy}, … */
    String id();

    /** What this provider offers, in prompt order. */
    List<ToolDefinition> definitions();

    /** True when this provider owns the tool with that id. */
    boolean owns(String toolId);

    /**
     * Run one call. The registry has already checked the capabilities and the
     * platform; this method does the work and reports what happened.
     */
    ToolResult run(ToolContext context, String toolId, JSONObject args);
}
