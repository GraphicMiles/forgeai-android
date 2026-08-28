package ai.luna.contracts;

/**
 * What came back from one tool call.
 *
 * <p>A failed tool is information, not a crash — that rule already holds in
 * Luna and it becomes load-bearing once tools come from strangers. Every
 * implementation returns one of these; none of them throw.
 */
public final class ToolResult {

    public final boolean ok;

    /** What the model is told. Short, factual, no stack traces. */
    public final String observation;

    /** Why it failed, when it did. Null on success. */
    public final String error;

    /** Milliseconds spent inside the tool. */
    public final long tookMs;

    private ToolResult(boolean ok, String observation, String error, long tookMs) {
        this.ok = ok;
        this.observation = observation == null ? "" : observation;
        this.error = error;
        this.tookMs = tookMs;
    }

    public static ToolResult ok(String observation) {
        return new ToolResult(true, observation, null, 0L);
    }

    public static ToolResult ok(String observation, long tookMs) {
        return new ToolResult(true, observation, null, tookMs);
    }

    public static ToolResult failed(String reason) {
        return new ToolResult(false, "Failed: " + reason, reason, 0L);
    }

    /** The tool ran past its own timeout and was abandoned. */
    public static ToolResult unfinished(String toolId, long timeoutMs) {
        String reason = toolId + " took longer than " + (timeoutMs / 1000L)
            + " seconds, so it was abandoned. Try a smaller piece of the same job.";
        return new ToolResult(false, reason, reason, timeoutMs);
    }

    /** The capability this tool needs was not granted. */
    public static ToolResult denied(String capability) {
        String reason = "That needs permission to " + Capability.describe(capability).toLowerCase()
            + ", which this agent does not have.";
        return new ToolResult(false, reason, reason, 0L);
    }

    public ToolResult withTiming(long millis) {
        return new ToolResult(ok, observation, error, millis);
    }
}
