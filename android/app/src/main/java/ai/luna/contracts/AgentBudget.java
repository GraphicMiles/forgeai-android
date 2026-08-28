package ai.luna.contracts;

import org.json.JSONObject;

/**
 * What a run is allowed to spend.
 *
 * <p>{@code RunGuards} grew this out of three numbers hidden inside one class.
 * As a value it can be handed to a child, and — the part that matters — it can
 * only ever be narrowed on the way down. A parent with four steps left cannot
 * give a child eight, whatever the child's definition says.
 */
public final class AgentBudget {

    public final int steps;
    public final int seconds;
    public final int cloudCalls;

    /** How deep the family tree may go below this run. */
    public final int depth;

    public AgentBudget(int steps, int seconds, int cloudCalls, int depth) {
        this.steps = Math.max(0, steps);
        this.seconds = Math.max(0, seconds);
        this.cloudCalls = Math.max(0, cloudCalls);
        this.depth = Math.max(0, depth);
    }

    public static AgentBudget of(int steps, int seconds, int cloudCalls) {
        return new AgentBudget(steps, seconds, cloudCalls, 1);
    }

    public static AgentBudget none() {
        return new AgentBudget(0, 0, 0, 0);
    }

    /** The smaller of the two in every direction. Never the larger. */
    public AgentBudget narrow(AgentBudget other) {
        if (other == null) {
            return this;
        }
        return new AgentBudget(
            Math.min(steps, other.steps),
            Math.min(seconds, other.seconds),
            Math.min(cloudCalls, other.cloudCalls),
            Math.min(depth, other.depth));
    }

    /** What a child may have: a share of what is left, one level shallower. */
    public AgentBudget forChild(int shareSteps, int shareSeconds, int shareCloudCalls) {
        return new AgentBudget(
            Math.min(steps, shareSteps),
            Math.min(seconds, shareSeconds),
            Math.min(cloudCalls, shareCloudCalls),
            Math.max(0, depth - 1));
    }

    /** What is left after a child has spent some of it. */
    public AgentBudget minus(AgentBudget spent) {
        if (spent == null) {
            return this;
        }
        return new AgentBudget(steps - spent.steps, seconds - spent.seconds,
            cloudCalls - spent.cloudCalls, depth);
    }

    public boolean exhausted() {
        return steps <= 0 || seconds <= 0;
    }

    public boolean maySpawn() {
        return depth > 0 && !exhausted();
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("steps", steps);
            out.put("seconds", seconds);
            out.put("cloudCalls", cloudCalls);
            out.put("depth", depth);
        } catch (Exception ignored) {
            // Four integers always serialise.
        }
        return out;
    }

    public static AgentBudget fromJson(JSONObject json) {
        if (json == null) {
            return none();
        }
        return new AgentBudget(json.optInt("steps"), json.optInt("seconds"),
            json.optInt("cloudCalls"), json.optInt("depth"));
    }
}
