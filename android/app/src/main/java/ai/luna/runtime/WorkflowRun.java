package ai.luna.runtime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * One run of a workflow: what is known, what happened, and where it stopped.
 *
 * <p>This is the structured trace the platform plan asks for. A transcript is a
 * story; this is a record — every step with its node id, its outcome and the
 * variables at the time — which is what makes replaying from a checkpoint
 * possible rather than aspirational.
 */
public final class WorkflowRun {

    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String STOPPED = "stopped";
    public static final String DENIED = "denied";
    public static final String EXHAUSTED = "exhausted";

    public final String workflowId;
    public final String runId;

    private final JSONObject variables = new JSONObject();
    private final JSONArray steps = new JSONArray();

    private String status = RUNNING;
    private String message = "";
    private String lastNode = "";
    private int taken;

    public WorkflowRun(String workflowId, String runId) {
        this.workflowId = workflowId;
        this.runId = runId;
    }

    // --- what is known --------------------------------------------------------

    public void set(String key, Object value) {
        if (key == null || key.isEmpty()) {
            return;
        }
        try {
            variables.put(key, value == null ? "" : value);
        } catch (Exception ignored) {
            // A value that will not serialise is a value not worth keeping.
        }
    }

    public void setAll(JSONObject values) {
        if (values == null) {
            return;
        }
        for (Iterator<String> keys = values.keys(); keys.hasNext(); ) {
            String key = keys.next();
            set(key, values.opt(key));
        }
    }

    public String text(String key) {
        return variables.optString(key, "");
    }

    public Object value(String key) {
        return variables.opt(key);
    }

    public boolean knows(String key) {
        return variables.has(key);
    }

    public JSONObject variables() {
        return variables;
    }

    /** Fills {{name}} from what is known. An unknown name becomes nothing. */
    public String fill(String template) {
        if (template == null || template.indexOf("{{") < 0) {
            return template == null ? "" : template;
        }
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < template.length()) {
            int open = template.indexOf("{{", index);
            if (open < 0) {
                out.append(template.substring(index));
                break;
            }
            int close = template.indexOf("}}", open);
            if (close < 0) {
                out.append(template.substring(index));
                break;
            }
            out.append(template, index, open);
            String key = template.substring(open + 2, close).trim();
            out.append(variables.has(key) ? String.valueOf(variables.opt(key)) : "");
            index = close + 2;
        }
        return out.toString();
    }

    // --- what happened --------------------------------------------------------

    /** Records one step. The record is the thing a replay reads back. */
    public void record(String nodeId, String type, String outcome, String detail) {
        taken++;
        lastNode = nodeId;
        try {
            JSONObject step = new JSONObject();
            step.put("n", taken);
            step.put("node", nodeId);
            step.put("type", type);
            step.put("outcome", outcome);
            step.put("detail", detail == null ? "" : detail);
            step.put("at", System.currentTimeMillis());
            steps.put(step);
        } catch (Exception ignored) {
            // The step still counted even if it could not be written down.
        }
    }

    public int taken() {
        return taken;
    }

    public String lastNode() {
        return lastNode;
    }

    public JSONArray steps() {
        return steps;
    }

    public List<String> nodesVisited() {
        List<String> out = new ArrayList<>();
        for (int index = 0; index < steps.length(); index++) {
            JSONObject step = steps.optJSONObject(index);
            if (step != null) {
                out.add(step.optString("node", ""));
            }
        }
        return out;
    }

    // --- how it ended ---------------------------------------------------------

    public void finish(String status, String message) {
        this.status = status;
        this.message = message == null ? "" : message;
    }

    public String status() {
        return status;
    }

    public String message() {
        return message;
    }

    public boolean running() {
        return RUNNING.equals(status);
    }

    public boolean ok() {
        return DONE.equals(status);
    }

    /** The whole run, as data: what was known, every step, how it ended. */
    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("workflow", workflowId);
            out.put("run", runId);
            out.put("status", status);
            out.put("message", message);
            out.put("steps", steps);
            out.put("variables", variables);
            out.put("lastNode", lastNode);
        } catch (Exception ignored) {
            // Nothing here is unserialisable.
        }
        return out;
    }

    /**
     * A run resumed from an earlier one.
     *
     * <p>The variables come back, the step count starts again, and the node to
     * resume at is the caller's decision — usually the one after the last
     * recorded step.
     */
    public static WorkflowRun resume(JSONObject saved, String runId) {
        WorkflowRun run = new WorkflowRun(saved.optString("workflow", ""), runId);
        run.setAll(saved.optJSONObject("variables"));
        run.lastNode = saved.optString("lastNode", "");
        return run;
    }
}
