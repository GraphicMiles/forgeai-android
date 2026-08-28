package ai.luna.builtin;

import ai.luna.contracts.Capability;
import ai.luna.contracts.RiskLevel;
import ai.luna.contracts.ToolContext;
import ai.luna.contracts.ToolDefinition;
import ai.luna.contracts.ToolResult;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The two tools that talk to the person rather than the device.
 *
 * <p>Neither runs here. {@code ask_user} parks the whole turn on an answer and
 * {@code respond} ends it, so both are handled by the runtime before a provider
 * is ever reached; they are declared so that the prompt, the permission screen
 * and the registry all see one complete list.
 */
public final class UserTools extends BuiltinProvider {

    public UserTools() {
        super(declare());
    }

    @Override
    public String id() {
        return "core.user";
    }

    @Override
    public ToolResult run(ToolContext context, String toolId, JSONObject args) {
        return ToolResult.failed(toolId + " is answered by the runtime, not by a tool.");
    }

    private static List<ToolDefinition> declare() {
        List<ToolDefinition> all = new ArrayList<>();
        all.add(ToolDefinition.of("ask_user", "Ask the person")
            .description("Stop and wait for a real answer")
            .input("question", "What to ask")
            .required("question")
            .capabilities(Capability.USER_ASK)
            .risk(RiskLevel.MEDIUM)
            .timeout(600000L)
            .build());
        all.add(ToolDefinition.of("respond", "Answer")
            .description("Finish the job and say what happened")
            .input("text", "The answer, in plain sentences")
            .risk(RiskLevel.LOW)
            .build());
        return all;
    }
}
