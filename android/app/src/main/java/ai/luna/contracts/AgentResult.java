package ai.luna.contracts;

import org.json.JSONObject;

/** What came back from a piece of work handed to another agent. */
public final class AgentResult {

    public final boolean ok;
    public final String agentId;

    /** What it found or did, in words the parent can put in its own answer. */
    public final String summary;

    /** What it actually spent. */
    public final AgentBudget spent;

    /** Why it could not, when it could not. */
    public final String refusal;

    private AgentResult(boolean ok, String agentId, String summary, AgentBudget spent,
                        String refusal) {
        this.ok = ok;
        this.agentId = agentId;
        this.summary = summary == null ? "" : summary;
        this.spent = spent == null ? AgentBudget.none() : spent;
        this.refusal = refusal;
    }

    public static AgentResult of(String agentId, String summary, AgentBudget spent) {
        return new AgentResult(true, agentId, summary, spent, null);
    }

    public static AgentResult refused(String agentId, String reason) {
        return new AgentResult(false, agentId, reason, AgentBudget.none(), reason);
    }

    /** The sentence the parent puts in its transcript, either way. */
    public String observation() {
        return ok ? summary : refusal;
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("ok", ok);
            out.put("agent", agentId);
            out.put("summary", summary);
            out.put("spent", spent.toJson());
            out.put("refusal", refusal == null ? "" : refusal);
        } catch (Exception ignored) {
            // Nothing here is unserialisable.
        }
        return out;
    }
}
