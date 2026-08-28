package ai.luna.runtime;

import ai.luna.contracts.ToolResult;

import org.json.JSONObject;

/**
 * What a workflow needs from the world outside it.
 *
 * <p>The engine decides what happens next; the host does it. Keeping the two
 * apart is what lets a workflow be tested without a model, a phone or a person,
 * and it is the same seam a laptop or a server will plug into later.
 */
public interface WorkflowHost {

    /** Ask the model. */
    String think(String prompt);

    /** Run a tool through the registry, with all its usual checks. */
    ToolResult tool(String toolId, JSONObject args);

    /** Put a decision to the person. False means no. */
    boolean approve(String message, String consequence);

    /** Put a question to the person. Empty means they never answered. */
    String ask(String question);

    /** Hand a piece of work to another agent. */
    String subAgent(String agentId, String task);

    /** Wait, interruptibly. Returns false when the run was stopped. */
    boolean pause(long millis);

    /** Has somebody asked for this to stop? */
    boolean stopped();

    /** Anything worth putting in the trace. */
    void event(JSONObject event);
}
