package ai.luna.runtime;

import ai.luna.contracts.ToolResult;
import ai.luna.contracts.WorkflowDefinition;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Runs a workflow.
 *
 * <p>One node at a time, on the calling thread, with a step budget and a stop
 * check between every step. Nothing here talks to a model, a tool, a person or
 * a clock directly — that all goes through {@link WorkflowHost}, which is why
 * the whole engine can be tested on a plain JVM in milliseconds.
 *
 * <p>The failure philosophy is the same as the tool registry's: a workflow that
 * cannot be run does not start, and a workflow that goes wrong halfway ends
 * with a status and a sentence rather than an exception.
 */
public final class WorkflowEngine {

    private final WorkflowHost host;

    public WorkflowEngine(WorkflowHost host) {
        this.host = host;
    }

    public WorkflowRun run(WorkflowDefinition workflow, JSONObject input) {
        return run(workflow, input, "");
    }

    /**
     * @param from node to start at, or empty for the workflow's own start —
     *             this is how a run resumes from a checkpoint
     */
    public WorkflowRun run(WorkflowDefinition workflow, JSONObject input, String from) {
        WorkflowRun run = new WorkflowRun(workflow.id,
            "run-" + System.currentTimeMillis());
        run.setAll(input);

        String problem = workflow.problem();
        if (problem != null) {
            run.finish(WorkflowRun.FAILED, problem);
            return run;
        }

        String current = from.isEmpty() || !workflow.has(from) ? workflow.start : from;
        while (!current.isEmpty() && run.running()) {
            if (host.stopped()) {
                run.finish(WorkflowRun.STOPPED, "Stopped.");
                break;
            }
            if (run.taken() >= workflow.maxSteps) {
                run.finish(WorkflowRun.EXHAUSTED,
                    "That workflow used all " + workflow.maxSteps + " of its steps.");
                break;
            }
            WorkflowDefinition.Node node = workflow.node(current);
            if (node == null) {
                run.finish(WorkflowRun.FAILED, "Step " + current + " does not exist.");
                break;
            }
            current = step(workflow, node, run);
        }
        if (run.running()) {
            run.finish(WorkflowRun.DONE, run.text("result"));
        }
        return run;
    }

    /** Runs one node and answers with the id of the next, or empty to stop. */
    private String step(WorkflowDefinition workflow, WorkflowDefinition.Node node,
                        WorkflowRun run) {
        String type = node.type;
        if (WorkflowDefinition.LLM.equals(type)) {
            return llm(node, run);
        }
        if (WorkflowDefinition.TOOL.equals(type)) {
            return tool(node, run);
        }
        if (WorkflowDefinition.CONDITION.equals(type)) {
            return condition(node, run);
        }
        if (WorkflowDefinition.LOOP.equals(type)) {
            return loop(workflow, node, run);
        }
        if (WorkflowDefinition.PARALLEL.equals(type)) {
            return parallel(workflow, node, run);
        }
        if (WorkflowDefinition.APPROVAL.equals(type)) {
            return approval(node, run);
        }
        if (WorkflowDefinition.HUMAN_INPUT.equals(type)) {
            return humanInput(node, run);
        }
        if (WorkflowDefinition.SUB_AGENT.equals(type)) {
            return subAgent(node, run);
        }
        if (WorkflowDefinition.TRANSFORM.equals(type)) {
            return transform(node, run);
        }
        if (WorkflowDefinition.VALIDATE.equals(type)) {
            return validate(node, run);
        }
        if (WorkflowDefinition.WAIT.equals(type)) {
            return wait(node, run);
        }
        if (WorkflowDefinition.END.equals(type)) {
            run.record(node.id, type, "done", "");
            run.finish(WorkflowRun.DONE, run.fill(node.text("message")));
            return "";
        }
        run.finish(WorkflowRun.FAILED, "Step " + node.id + " is a kind of step Luna does not "
            + "have.");
        return "";
    }

    // --- the nodes ------------------------------------------------------------

    private String llm(WorkflowDefinition.Node node, WorkflowRun run) {
        String prompt = run.fill(node.text("prompt"));
        String answer = host.think(prompt);
        store(node, run, answer == null ? "" : answer);
        run.record(node.id, node.type, "done", clip(answer));
        return node.next;
    }

    private String tool(WorkflowDefinition.Node node, WorkflowRun run) {
        JSONObject args = filled(node.config.optJSONObject("args"), run);
        ToolResult result = host.tool(node.text("tool"), args);
        if (result == null) {
            run.record(node.id, node.type, "failed", "no result");
            run.finish(WorkflowRun.FAILED, "Step " + node.id + " produced nothing.");
            return "";
        }
        store(node, run, result.observation);
        run.record(node.id, node.type, result.ok ? "done" : "failed", clip(result.observation));
        if (!result.ok && !node.config.optBoolean("continueOnFailure", false)) {
            run.finish(WorkflowRun.FAILED, result.observation);
            return "";
        }
        return node.next;
    }

    private String condition(WorkflowDefinition.Node node, WorkflowRun run) {
        boolean holds = holds(node.text("when"), run);
        run.record(node.id, node.type, holds ? "true" : "false", node.text("when"));
        String next = holds ? node.whenTrue : node.whenFalse;
        return next.isEmpty() ? node.next : next;
    }

    /**
     * A loop, over a list or a count, with its own ceiling.
     *
     * <p>The body is run by the same engine, one pass at a time, so a loop
     * cannot smuggle in an escape from the step budget: every pass costs steps
     * from the same total.
     */
    private String loop(WorkflowDefinition workflow, WorkflowDefinition.Node node,
                        WorkflowRun run) {
        List<Object> items = items(node, run);
        int limit = Math.min(items.size(), Math.max(0, node.number("max", 25)));
        String itemKey = node.text("as").isEmpty() ? "item" : node.text("as");
        for (int index = 0; index < limit && run.running(); index++) {
            if (host.stopped()) {
                run.finish(WorkflowRun.STOPPED, "Stopped.");
                return "";
            }
            run.set(itemKey, items.get(index));
            run.set(itemKey + "Index", index);
            String inner = node.body;
            while (!inner.isEmpty() && run.running()) {
                if (run.taken() >= workflow.maxSteps) {
                    run.finish(WorkflowRun.EXHAUSTED,
                        "That workflow used all " + workflow.maxSteps + " of its steps.");
                    return "";
                }
                WorkflowDefinition.Node step = workflow.node(inner);
                if (step == null) {
                    break;
                }
                inner = step(workflow, step, run);
            }
        }
        run.record(node.id, node.type, "done", limit + " of " + items.size());
        return node.next;
    }

    /**
     * Independent branches.
     *
     * <p>They are independent, not simultaneous. A phone has one tool runner and
     * one model; running branches at the same time would mean two loads of a
     * 3B model in memory. The contract this node makes is that the branches do
     * not depend on each other and all of them must finish — which is the part
     * a workflow author actually relies on.
     */
    private String parallel(WorkflowDefinition workflow, WorkflowDefinition.Node node,
                            WorkflowRun run) {
        for (String branch : node.branches) {
            String inner = branch;
            while (!inner.isEmpty() && run.running()) {
                WorkflowDefinition.Node step = workflow.node(inner);
                if (step == null) {
                    break;
                }
                if (run.taken() >= workflow.maxSteps) {
                    run.finish(WorkflowRun.EXHAUSTED,
                        "That workflow used all " + workflow.maxSteps + " of its steps.");
                    return "";
                }
                inner = step(workflow, step, run);
                if (inner.equals(node.next)) {
                    // A branch that rejoins the main line stops being a branch.
                    break;
                }
            }
        }
        run.record(node.id, node.type, "done", node.branches.size() + " branches");
        return node.next;
    }

    private String approval(WorkflowDefinition.Node node, WorkflowRun run) {
        String message = run.fill(node.text("message"));
        boolean allowed = host.approve(message, run.fill(node.text("consequence")));
        run.record(node.id, node.type, allowed ? "allowed" : "denied", message);
        if (allowed) {
            return node.whenTrue.isEmpty() ? node.next : node.whenTrue;
        }
        if (!node.whenFalse.isEmpty()) {
            return node.whenFalse;
        }
        run.finish(WorkflowRun.DENIED, "You said no to: " + message);
        return "";
    }

    private String humanInput(WorkflowDefinition.Node node, WorkflowRun run) {
        String question = run.fill(node.text("question"));
        String answer = host.ask(question);
        store(node, run, answer == null ? "" : answer);
        boolean answered = answer != null && !answer.trim().isEmpty();
        run.record(node.id, node.type, answered ? "done" : "unanswered", question);
        if (!answered && node.config.optBoolean("required", true)) {
            run.finish(WorkflowRun.STOPPED, "Nobody answered: " + question);
            return "";
        }
        return node.next;
    }

    private String subAgent(WorkflowDefinition.Node node, WorkflowRun run) {
        String agent = node.text("agent");
        String task = run.fill(node.text("task"));
        String result = host.subAgent(agent, task);
        store(node, run, result == null ? "" : result);
        run.record(node.id, node.type, "done", agent + ": " + clip(task));
        return node.next;
    }

    /**
     * Reshaping what is already known, without a model and without a tool.
     *
     * <p>Deliberately a short list of operations rather than an expression
     * language. A scripting language inside a workflow is a second product, and
     * the platform plan says not to build one.
     */
    private String transform(WorkflowDefinition.Node node, WorkflowRun run) {
        String operation = node.text("op").toLowerCase(Locale.US);
        String source = run.fill(node.text("value").isEmpty()
            ? "{{" + node.text("from") + "}}" : node.text("value"));
        String result;
        if ("upper".equals(operation)) {
            result = source.toUpperCase(Locale.US);
        } else if ("lower".equals(operation)) {
            result = source.toLowerCase(Locale.US);
        } else if ("trim".equals(operation)) {
            result = source.trim();
        } else if ("slice".equals(operation)) {
            int length = Math.max(0, node.number("length", 200));
            result = source.length() <= length ? source : source.substring(0, length);
        } else if ("count".equals(operation)) {
            result = String.valueOf(list(run.value(node.text("from"))).size());
        } else if ("join".equals(operation)) {
            StringBuilder joined = new StringBuilder();
            String separator = node.config.optString("separator", ", ");
            for (Object item : list(run.value(node.text("from")))) {
                if (joined.length() > 0) {
                    joined.append(separator);
                }
                joined.append(String.valueOf(item));
            }
            result = joined.toString();
        } else {
            result = source;
        }
        store(node, run, result);
        run.record(node.id, node.type, "done", operation);
        return node.next;
    }

    private String validate(WorkflowDefinition.Node node, WorkflowRun run) {
        boolean holds = holds(node.text("when"), run);
        run.record(node.id, node.type, holds ? "done" : "failed", node.text("when"));
        if (holds) {
            return node.next;
        }
        String reason = run.fill(node.text("message"));
        run.finish(WorkflowRun.FAILED, reason.isEmpty()
            ? "Step " + node.id + " found something wrong and stopped." : reason);
        return "";
    }

    private String wait(WorkflowDefinition.Node node, WorkflowRun run) {
        long millis = Math.max(0L, node.config.optLong("millis", 1000L));
        boolean finished = host.pause(millis);
        run.record(node.id, node.type, finished ? "done" : "stopped", millis + "ms");
        if (!finished) {
            run.finish(WorkflowRun.STOPPED, "Stopped.");
            return "";
        }
        return node.next;
    }

    // --- the small print ------------------------------------------------------

    private void store(WorkflowDefinition.Node node, WorkflowRun run, String value) {
        String key = node.text("as");
        run.set(key.isEmpty() ? node.id : key, value);
        run.set("result", value);
    }

    /**
     * A condition, kept deliberately small: {@code key op value}.
     *
     * <p>Supported: {@code is}, {@code not}, {@code contains}, {@code empty},
     * {@code present}, {@code >} and {@code <}. Anything else is false, because
     * a condition nobody can read is a bug waiting for a bad day.
     */
    boolean holds(String expression, WorkflowRun run) {
        if (expression == null) {
            return false;
        }
        // Split before filling, not after. "{{answer}} present" with nothing
        // known must ask whether an empty thing is present, not mistake the
        // word "present" for the whole expression.
        String[] parts = expression.trim().split("\\s+", 3);
        if (parts.length == 0 || parts[0].isEmpty()) {
            return false;
        }
        String left = run.fill(parts[0]).trim();
        if (parts.length == 1) {
            return !left.isEmpty() && !"false".equalsIgnoreCase(left) && !"0".equals(left);
        }
        String operator = parts[1].toLowerCase(Locale.US);
        String right = parts.length > 2 ? run.fill(parts[2]).trim() : "";
        if ("empty".equals(operator)) {
            return left.isEmpty();
        }
        if ("present".equals(operator)) {
            return !left.isEmpty();
        }
        if ("is".equals(operator) || "==".equals(operator)) {
            return left.equalsIgnoreCase(right);
        }
        if ("not".equals(operator) || "!=".equals(operator)) {
            return !left.equalsIgnoreCase(right);
        }
        if ("contains".equals(operator)) {
            return left.toLowerCase(Locale.US).contains(right.toLowerCase(Locale.US));
        }
        if (">".equals(operator) || "<".equals(operator)) {
            try {
                double one = Double.parseDouble(left);
                double two = Double.parseDouble(right);
                return ">".equals(operator) ? one > two : one < two;
            } catch (NumberFormatException notANumber) {
                return false;
            }
        }
        return false;
    }

    /** What a loop goes over: a named list, or a count. */
    private List<Object> items(WorkflowDefinition.Node node, WorkflowRun run) {
        String over = node.text("over");
        if (!over.isEmpty()) {
            return list(run.value(over));
        }
        List<Object> out = new ArrayList<>();
        int times = Math.max(0, node.number("times", 0));
        for (int index = 0; index < times; index++) {
            out.add(index);
        }
        return out;
    }

    private List<Object> list(Object value) {
        List<Object> out = new ArrayList<>();
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int index = 0; index < array.length(); index++) {
                out.add(array.opt(index));
            }
            return out;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.startsWith("[")) {
                try {
                    return list(new JSONArray(text));
                } catch (Exception notJson) {
                    // Fall through: it was just a string that started with a bracket.
                }
            }
            if (text.isEmpty()) {
                return out;
            }
            for (String part : text.split("\n")) {
                if (!part.trim().isEmpty()) {
                    out.add(part.trim());
                }
            }
            return out;
        }
        if (value != null) {
            out.add(value);
        }
        return out;
    }

    /** Fills every string in an argument object from what the run knows. */
    private JSONObject filled(JSONObject template, WorkflowRun run) {
        JSONObject out = new JSONObject();
        if (template == null) {
            return out;
        }
        for (Iterator<String> keys = template.keys(); keys.hasNext(); ) {
            String key = keys.next();
            Object value = template.opt(key);
            try {
                out.put(key, value instanceof String ? run.fill((String) value) : value);
            } catch (Exception ignored) {
                // A value that will not copy is a value the tool will miss.
            }
        }
        return out;
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }
}
