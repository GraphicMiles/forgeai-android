package ai.luna.runtime;

import ai.luna.contracts.WorkflowDefinition;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every workflow installed, and the reason any of them were refused.
 *
 * <p>A workflow is checked when it is registered rather than when it is run: a
 * broken one should be a message in a list, not a surprise halfway through a
 * job that has already changed somebody's files.
 */
public final class WorkflowRegistry {

    private final Map<String, WorkflowDefinition> workflows = new LinkedHashMap<>();
    private final Map<String, String> refused = new LinkedHashMap<>();

    /** Registers one. Returns null when it was accepted, or why it was not. */
    public String register(JSONObject json) {
        WorkflowDefinition workflow = WorkflowDefinition.fromJson(json);
        String problem = workflow.problem();
        if (problem != null) {
            refused.put(workflow.id.isEmpty() ? "?" : workflow.id, problem);
            return problem;
        }
        if (workflows.containsKey(workflow.id)) {
            return workflow.name + " is already installed.";
        }
        workflows.put(workflow.id, workflow);
        return null;
    }

    public boolean has(String id) {
        return workflows.containsKey(id);
    }

    public WorkflowDefinition get(String id) {
        return workflows.get(id);
    }

    public List<WorkflowDefinition> all() {
        return Collections.unmodifiableList(new ArrayList<>(workflows.values()));
    }

    public Map<String, String> refusals() {
        return Collections.unmodifiableMap(refused);
    }

    public JSONArray describe() {
        JSONArray out = new JSONArray();
        for (WorkflowDefinition workflow : workflows.values()) {
            try {
                JSONObject row = new JSONObject();
                row.put("id", workflow.id);
                row.put("name", workflow.name);
                row.put("description", workflow.description);
                row.put("version", workflow.version);
                row.put("steps", workflow.all().size());
                row.put("maxSteps", workflow.maxSteps);
                out.put(row);
            } catch (Exception ignored) {
                // One unprintable workflow does not sink the list.
            }
        }
        return out;
    }
}
