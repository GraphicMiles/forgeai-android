package ai.luna.contracts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A job written down as steps instead of hoped for in a prompt.
 *
 * <p>An agent loop is a good way to handle a request nobody could have
 * anticipated and a poor way to do the same thing every Monday. A workflow is
 * the second case: fixed nodes, explicit branches, a declared budget, and a
 * trace you can replay. It is data, so it can arrive in a plugin.
 */
public final class WorkflowDefinition {

    // --- what a node can be ---------------------------------------------------

    /** Ask a model. */
    public static final String LLM = "llm";
    /** Run one tool. */
    public static final String TOOL = "tool";
    /** Go one way or the other. */
    public static final String CONDITION = "condition";
    /** Repeat a body, over a list or a count. */
    public static final String LOOP = "loop";
    /** Independent branches, all of which must finish. */
    public static final String PARALLEL = "parallel";
    /** Stop until the person allows it. */
    public static final String APPROVAL = "approval";
    /** Stop until the person answers. */
    public static final String HUMAN_INPUT = "human_input";
    /** Hand a piece of work to another agent. */
    public static final String SUB_AGENT = "sub_agent";
    /** Reshape what is already known. */
    public static final String TRANSFORM = "transform";
    /** Refuse to carry on with something that is wrong. */
    public static final String VALIDATE = "validate";
    /** Wait. */
    public static final String WAIT = "wait";
    /** Finish. */
    public static final String END = "end";

    public static final List<String> TYPES = Collections.unmodifiableList(java.util.Arrays.asList(
        LLM, TOOL, CONDITION, LOOP, PARALLEL, APPROVAL, HUMAN_INPUT, SUB_AGENT, TRANSFORM,
        VALIDATE, WAIT, END));

    public static boolean isType(String type) {
        return TYPES.contains(type);
    }

    /** One step. */
    public static final class Node {

        public final String id;
        public final String type;

        /** Everything type-specific: prompt, tool, expression, and so on. */
        public final JSONObject config;

        /** Where to go next, or empty to stop. */
        public final String next;

        /** Where a condition goes when it holds, and when it does not. */
        public final String whenTrue;
        public final String whenFalse;

        /** The first node of a loop body, or the branches of a parallel. */
        public final String body;
        public final List<String> branches;

        Node(String id, String type, JSONObject config, String next, String whenTrue,
             String whenFalse, String body, List<String> branches) {
            this.id = id;
            this.type = type;
            this.config = config == null ? new JSONObject() : config;
            this.next = next;
            this.whenTrue = whenTrue;
            this.whenFalse = whenFalse;
            this.body = body;
            this.branches = Collections.unmodifiableList(branches);
        }

        public String text(String key) {
            return config.optString(key, "");
        }

        public int number(String key, int fallback) {
            return config.optInt(key, fallback);
        }

        public JSONObject toJson() throws JSONException {
            JSONObject out = new JSONObject();
            out.put("id", id);
            out.put("type", type);
            out.put("config", config);
            out.put("next", next);
            out.put("whenTrue", whenTrue);
            out.put("whenFalse", whenFalse);
            out.put("body", body);
            out.put("branches", new JSONArray(branches));
            return out;
        }
    }

    public final String id;
    public final String name;
    public final String description;
    public final String version;

    /** Where the run begins. */
    public final String start;

    /** Nodes one may take before the run is abandoned. */
    public final int maxSteps;

    private final Map<String, Node> nodes;

    private WorkflowDefinition(String id, String name, String description, String version,
                               String start, int maxSteps, Map<String, Node> nodes) {
        this.id = id;
        this.name = name.isEmpty() ? id : name;
        this.description = description;
        this.version = version;
        this.start = start;
        this.maxSteps = maxSteps <= 0 ? 60 : maxSteps;
        this.nodes = nodes;
    }

    public Node node(String id) {
        return nodes.get(id);
    }

    public boolean has(String id) {
        return nodes.containsKey(id);
    }

    public List<Node> all() {
        return Collections.unmodifiableList(new ArrayList<>(nodes.values()));
    }

    /**
     * Why this workflow cannot be run, or null.
     *
     * <p>Checked once, before anything happens, so a broken workflow fails as a
     * workflow rather than halfway through doing real work to a real folder.
     */
    public String problem() {
        if (id.isEmpty()) {
            return "That workflow has no id.";
        }
        if (nodes.isEmpty()) {
            return "That workflow has no steps.";
        }
        if (!has(start)) {
            return "That workflow does not say where to start.";
        }
        for (Node node : nodes.values()) {
            if (!isType(node.type)) {
                return "Step " + node.id + " is a kind of step Luna does not have: " + node.type;
            }
            String dangling = dangling(node);
            if (dangling != null) {
                return "Step " + node.id + " points at " + dangling + ", which does not exist.";
            }
            if (CONDITION.equals(node.type) && node.text("when").isEmpty()) {
                return "Step " + node.id + " is a condition with nothing to decide.";
            }
            if (TOOL.equals(node.type) && node.text("tool").isEmpty()) {
                return "Step " + node.id + " does not say which tool to use.";
            }
        }
        return null;
    }

    private String dangling(Node node) {
        for (String target : new String[] {node.next, node.whenTrue, node.whenFalse, node.body}) {
            if (!target.isEmpty() && !has(target)) {
                return target;
            }
        }
        for (String branch : node.branches) {
            if (!has(branch)) {
                return branch;
            }
        }
        return null;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("name", name);
        out.put("description", description);
        out.put("version", version);
        out.put("start", start);
        out.put("maxSteps", maxSteps);
        JSONArray steps = new JSONArray();
        for (Node node : nodes.values()) {
            steps.put(node.toJson());
        }
        out.put("nodes", steps);
        return out;
    }

    public static WorkflowDefinition fromJson(JSONObject json) {
        if (json == null) {
            json = new JSONObject();
        }
        Map<String, Node> nodes = new LinkedHashMap<>();
        JSONArray array = json.optJSONArray("nodes");
        String first = "";
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                JSONObject row = array.optJSONObject(index);
                if (row == null) {
                    continue;
                }
                String id = row.optString("id", "");
                if (id.isEmpty() || nodes.containsKey(id)) {
                    continue;
                }
                List<String> branches = new ArrayList<>();
                JSONArray raw = row.optJSONArray("branches");
                if (raw != null) {
                    for (int branch = 0; branch < raw.length(); branch++) {
                        String value = raw.optString(branch, "");
                        if (!value.isEmpty()) {
                            branches.add(value);
                        }
                    }
                }
                nodes.put(id, new Node(id,
                    row.optString("type", "").toLowerCase(Locale.US),
                    row.optJSONObject("config"),
                    row.optString("next", ""),
                    row.optString("whenTrue", ""),
                    row.optString("whenFalse", ""),
                    row.optString("body", ""),
                    branches));
                if (first.isEmpty()) {
                    first = id;
                }
            }
        }
        String start = json.optString("start", "");
        return new WorkflowDefinition(
            json.optString("id", ""),
            json.optString("name", ""),
            json.optString("description", ""),
            json.optString("version", "1.0.0"),
            start.isEmpty() ? first : start,
            json.optInt("maxSteps", 0),
            nodes);
    }
}
