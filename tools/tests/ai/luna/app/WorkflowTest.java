package ai.luna.app;

import ai.luna.contracts.ToolResult;
import ai.luna.contracts.WorkflowDefinition;
import ai.luna.runtime.WorkflowEngine;
import ai.luna.runtime.WorkflowHost;
import ai.luna.runtime.WorkflowRegistry;
import ai.luna.runtime.WorkflowRun;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Jobs written down as steps.
 *
 * <p>The engine is tested against a recording host: no model, no phone, no
 * person, no clock. What is being checked is control flow and honesty — that a
 * branch goes the right way, that a budget cannot be escaped through a loop,
 * that a refused approval ends the run, and that the trace says what happened.
 */
public final class WorkflowTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        validation();
        straightLine();
        templating();
        branching();
        looping();
        parallelBranches();
        approvals();
        questions();
        transforms();
        validating();
        budgets();
        stopping();
        theTrace();
        registry();

        System.out.println();
        if (failed > 0) {
            System.out.println(failed + " FAILED, " + passed + " passed");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    // --- refusing to start ----------------------------------------------------

    private static void validation() {
        check("a workflow with no steps is refused",
            WorkflowDefinition.fromJson(new JSONObject()).problem() != null);

        JSONObject dangling = flow("acme.dangling",
            node("one", WorkflowDefinition.LLM, config("prompt", "hello"), "nowhere"));
        check("a step pointing at nothing is refused",
            WorkflowDefinition.fromJson(dangling).problem().contains("does not exist"));

        JSONObject strange = flow("acme.strange", node("one", "telepathy", new JSONObject(), ""));
        check("a kind of step Luna does not have is refused",
            WorkflowDefinition.fromJson(strange).problem().contains("kind of step"));

        JSONObject toolless = flow("acme.toolless",
            node("one", WorkflowDefinition.TOOL, new JSONObject(), ""));
        check("a tool step with no tool is refused",
            WorkflowDefinition.fromJson(toolless).problem().contains("which tool"));

        JSONObject blind = flow("acme.blind",
            node("one", WorkflowDefinition.CONDITION, new JSONObject(), ""));
        check("a condition with nothing to decide is refused",
            WorkflowDefinition.fromJson(blind).problem().contains("nothing to decide"));

        Recorder host = new Recorder();
        WorkflowRun run = new WorkflowEngine(host).run(
            WorkflowDefinition.fromJson(dangling), new JSONObject());
        check("a broken workflow never runs a single step", host.calls.isEmpty());
        check("and says why", run.status().equals(WorkflowRun.FAILED));
    }

    // --- the ordinary case ----------------------------------------------------

    private static void straightLine() {
        JSONObject json = flow("acme.line",
            node("think", WorkflowDefinition.LLM, config("prompt", "Summarise", "as", "summary"),
                "save"),
            node("save", WorkflowDefinition.TOOL,
                tool("write_file", "notes.md", "{{summary}}"), "done"),
            node("done", WorkflowDefinition.END, config("message", "Saved the summary."), ""));

        Recorder host = new Recorder();
        host.answer = "It is about invoices.";
        WorkflowRun run = new WorkflowEngine(host).run(
            WorkflowDefinition.fromJson(json), new JSONObject());

        check("the run finishes", run.ok());
        check("with the workflow's own words", run.message().equals("Saved the summary."));
        check("the model was asked once", host.calls.contains("think:Summarise"));
        check("the tool was run", host.calls.contains("tool:write_file"));
        check("three steps were recorded", run.taken() == 3);
        check("in order", run.nodesVisited().toString().equals("[think, save, done]"));
    }

    private static void templating() {
        JSONObject json = flow("acme.fill",
            node("think", WorkflowDefinition.LLM,
                config("prompt", "Tell me about {{topic}} in {{style}}", "as", "answer"), ""));
        JSONObject input = new JSONObject();
        put(input, "topic", "invoices");

        Recorder host = new Recorder();
        new WorkflowEngine(host).run(WorkflowDefinition.fromJson(json), input);
        check("what is known is filled in",
            host.calls.contains("think:Tell me about invoices in "));

        WorkflowRun run = new WorkflowEngine(host).run(
            WorkflowDefinition.fromJson(json), input);
        check("the answer is stored under its own name", run.text("answer").equals(host.answer));
        check("and as the running result", run.text("result").equals(host.answer));
    }

    // --- going one way or the other -------------------------------------------

    private static void branching() {
        JSONObject json = flow("acme.branch",
            branch("check", "{{count}} > 3", "many", "few"),
            node("many", WorkflowDefinition.END, config("message", "Lots."), ""),
            node("few", WorkflowDefinition.END, config("message", "Not many."), ""));
        WorkflowDefinition workflow = WorkflowDefinition.fromJson(json);

        check("a condition that holds goes one way",
            end(workflow, "count", "9").equals("Lots."));
        check("and one that does not goes the other",
            end(workflow, "count", "1").equals("Not many."));
        check("a comparison against nonsense is simply false",
            end(workflow, "count", "many").equals("Not many."));

        JSONObject words = flow("acme.words",
            branch("check", "{{name}} contains invoice", "yes", "no"),
            node("yes", WorkflowDefinition.END, config("message", "Invoice."), ""),
            node("no", WorkflowDefinition.END, config("message", "Something else."), ""));
        WorkflowDefinition byWord = WorkflowDefinition.fromJson(words);
        check("contains works on text",
            end(byWord, "name", "March-invoice.pdf").equals("Invoice."));
        check("and is not fooled", end(byWord, "name", "receipt.pdf").equals("Something else."));

        JSONObject missing = flow("acme.missing",
            branch("check", "{{nothing}} present", "yes", "no"),
            node("yes", WorkflowDefinition.END, config("message", "Present."), ""),
            node("no", WorkflowDefinition.END, config("message", "Absent."), ""));
        check("something never set is absent",
            end(WorkflowDefinition.fromJson(missing), "other", "x").equals("Absent."));
    }

    private static void looping() {
        JSONObject json = flow("acme.loop",
            node("each", WorkflowDefinition.LOOP,
                config("over", "files", "as", "file", "max", "10"), "done", "read"),
            node("read", WorkflowDefinition.TOOL, tool("read_file", "{{file}}", ""), ""),
            node("done", WorkflowDefinition.END, config("message", "Read them all."), ""));

        JSONObject input = new JSONObject();
        put(input, "files", new JSONArray().put("a.md").put("b.md").put("c.md"));

        Recorder host = new Recorder();
        WorkflowRun run = new WorkflowEngine(host).run(
            WorkflowDefinition.fromJson(json), input);
        check("the body runs once per item", count(host.calls, "tool:read_file") == 3);
        check("each pass sees its own item", host.paths.contains("b.md"));
        check("and the loop finishes", run.ok());

        JSONObject capped = flow("acme.capped",
            node("each", WorkflowDefinition.LOOP,
                config("over", "files", "as", "file", "max", "2"), "done", "read"),
            node("read", WorkflowDefinition.TOOL, tool("read_file", "{{file}}", ""), ""),
            node("done", WorkflowDefinition.END, config("message", "Enough."), ""));
        Recorder capHost = new Recorder();
        new WorkflowEngine(capHost).run(WorkflowDefinition.fromJson(capped), input);
        check("a loop's own ceiling is respected",
            count(capHost.calls, "tool:read_file") == 2);

        JSONObject times = flow("acme.times",
            node("each", WorkflowDefinition.LOOP, config("times", "4"), "done", "ping"),
            node("ping", WorkflowDefinition.LLM, config("prompt", "ping"), ""),
            node("done", WorkflowDefinition.END, config("message", "Pinged."), ""));
        Recorder timesHost = new Recorder();
        new WorkflowEngine(timesHost).run(WorkflowDefinition.fromJson(times), new JSONObject());
        check("a loop can simply count", count(timesHost.calls, "think:ping") == 4);
    }

    private static void parallelBranches() {
        JSONObject json = flow("acme.parallel",
            node("both", WorkflowDefinition.PARALLEL, new JSONObject(), "done",
                "", new String[] {"left", "right"}),
            node("left", WorkflowDefinition.LLM, config("prompt", "left", "as", "one"), ""),
            node("right", WorkflowDefinition.LLM, config("prompt", "right", "as", "two"), ""),
            node("done", WorkflowDefinition.END, config("message", "Both done."), ""));

        Recorder host = new Recorder();
        WorkflowRun run = new WorkflowEngine(host).run(
            WorkflowDefinition.fromJson(json), new JSONObject());
        check("every branch runs", host.calls.contains("think:left")
            && host.calls.contains("think:right"));
        check("and the run carries on afterwards", run.message().equals("Both done."));
        check("both results are kept", run.knows("one") && run.knows("two"));
    }

    // --- the person -----------------------------------------------------------

    private static void approvals() {
        JSONObject json = flow("acme.approve",
            node("ask", WorkflowDefinition.APPROVAL,
                config("message", "Delete {{file}}?", "consequence", "It cannot be undone."),
                "delete"),
            node("delete", WorkflowDefinition.TOOL, tool("delete_file", "{{file}}", ""), "done"),
            node("done", WorkflowDefinition.END, config("message", "Deleted."), ""));
        WorkflowDefinition workflow = WorkflowDefinition.fromJson(json);
        JSONObject input = new JSONObject();
        put(input, "file", "old.txt");

        Recorder yes = new Recorder();
        yes.approve = true;
        WorkflowRun allowed = new WorkflowEngine(yes).run(workflow, input);
        check("an allowed step goes ahead", yes.calls.contains("tool:delete_file"));
        check("and the question named the file", yes.calls.contains("approve:Delete old.txt?"));
        check("the run finishes", allowed.ok());

        Recorder no = new Recorder();
        no.approve = false;
        WorkflowRun denied = new WorkflowEngine(no).run(workflow, input);
        check("a refused step does not happen", !no.calls.contains("tool:delete_file"));
        check("and the run ends as denied", denied.status().equals(WorkflowRun.DENIED));
        check("saying what was refused", denied.message().contains("Delete old.txt?"));
    }

    private static void questions() {
        JSONObject json = flow("acme.ask",
            node("which", WorkflowDefinition.HUMAN_INPUT,
                config("question", "Which folder?", "as", "folder"), "use"),
            node("use", WorkflowDefinition.TOOL, tool("list_files", "{{folder}}", ""), ""));
        WorkflowDefinition workflow = WorkflowDefinition.fromJson(json);

        Recorder answered = new Recorder();
        answered.reply = "Billing";
        new WorkflowEngine(answered).run(workflow, new JSONObject());
        check("the answer is used", answered.paths.contains("Billing"));

        Recorder silent = new Recorder();
        silent.reply = "";
        WorkflowRun run = new WorkflowEngine(silent).run(workflow, new JSONObject());
        check("no answer stops the run", run.status().equals(WorkflowRun.STOPPED));
        check("without doing the next thing anyway", !silent.calls.contains("tool:list_files"));
    }

    // --- work without a model -------------------------------------------------

    private static void transforms() {
        JSONObject json = flow("acme.shape",
            node("join", WorkflowDefinition.TRANSFORM,
                config("op", "join", "from", "files", "as", "list", "separator", " | "), "count"),
            node("count", WorkflowDefinition.TRANSFORM,
                config("op", "count", "from", "files", "as", "howMany"), "shout"),
            node("shout", WorkflowDefinition.TRANSFORM,
                config("op", "upper", "value", "{{list}}", "as", "loud"), ""));
        JSONObject input = new JSONObject();
        put(input, "files", new JSONArray().put("a.md").put("b.md"));

        WorkflowRun run = new WorkflowEngine(new Recorder()).run(
            WorkflowDefinition.fromJson(json), input);
        check("a list can be joined", run.text("list").equals("a.md | b.md"));
        check("counted", run.text("howMany").equals("2"));
        check("and shouted", run.text("loud").equals("A.MD | B.MD"));
        check("none of which asked a model", run.ok());
    }

    private static void validating() {
        JSONObject json = flow("acme.check",
            node("guard", WorkflowDefinition.VALIDATE,
                config("when", "{{answer}} present", "message", "The model said nothing."),
                "done"),
            node("done", WorkflowDefinition.END, config("message", "Fine."), ""));
        WorkflowDefinition workflow = WorkflowDefinition.fromJson(json);

        JSONObject good = new JSONObject();
        put(good, "answer", "something");
        check("valid input carries on",
            new WorkflowEngine(new Recorder()).run(workflow, good).ok());

        WorkflowRun bad = new WorkflowEngine(new Recorder()).run(workflow, new JSONObject());
        check("invalid input stops the run", bad.status().equals(WorkflowRun.FAILED));
        check("with the workflow's own complaint",
            bad.message().equals("The model said nothing."));
    }

    // --- limits ---------------------------------------------------------------

    private static void budgets() {
        JSONObject json = capped("acme.forever", 5,
            node("each", WorkflowDefinition.LOOP,
                config("over", "files", "as", "file", "max", "100"), "done", "read"),
            node("read", WorkflowDefinition.TOOL, tool("read_file", "{{file}}", ""), ""),
            node("done", WorkflowDefinition.END, config("message", "Done."), ""));
        JSONArray many = new JSONArray();
        for (int index = 0; index < 50; index++) {
            many.put("file" + index + ".md");
        }
        JSONObject input = new JSONObject();
        put(input, "files", many);

        Recorder host = new Recorder();
        WorkflowRun run = new WorkflowEngine(host).run(
            WorkflowDefinition.fromJson(json), input);
        check("a loop cannot spend more than the whole budget",
            run.status().equals(WorkflowRun.EXHAUSTED));
        check("and stops near the limit", run.taken() <= 6);
        check("the tool did not run fifty times", count(host.calls, "tool:read_file") <= 5);

        JSONObject failing = flow("acme.failing",
            node("save", WorkflowDefinition.TOOL, tool("write_file", "x.md", "hi"), "done"),
            node("done", WorkflowDefinition.END, config("message", "Saved."), ""));
        Recorder broken = new Recorder();
        broken.toolFails = true;
        WorkflowRun failedRun = new WorkflowEngine(broken).run(
            WorkflowDefinition.fromJson(failing), new JSONObject());
        check("a failed tool ends the run by default",
            failedRun.status().equals(WorkflowRun.FAILED));

        JSONObject tolerant = flow("acme.tolerant",
            node("save", WorkflowDefinition.TOOL,
                merge(tool("write_file", "x.md", "hi"), "continueOnFailure", true), "done"),
            node("done", WorkflowDefinition.END, config("message", "Carried on."), ""));
        WorkflowRun carried = new WorkflowEngine(broken).run(
            WorkflowDefinition.fromJson(tolerant), new JSONObject());
        check("unless the workflow said to carry on", carried.ok());
    }

    private static void stopping() {
        JSONObject json = flow("acme.stop",
            node("each", WorkflowDefinition.LOOP, config("times", "10"), "done", "ping"),
            node("ping", WorkflowDefinition.LLM, config("prompt", "ping"), ""),
            node("done", WorkflowDefinition.END, config("message", "Done."), ""));
        Recorder host = new Recorder();
        host.stopAfter = 2;
        WorkflowRun run = new WorkflowEngine(host).run(
            WorkflowDefinition.fromJson(json), new JSONObject());
        check("stop is obeyed inside a loop", run.status().equals(WorkflowRun.STOPPED));
        check("and nothing else is asked", count(host.calls, "think:ping") <= 3);
    }

    private static void theTrace() {
        JSONObject json = flow("acme.trace",
            node("think", WorkflowDefinition.LLM, config("prompt", "hello", "as", "answer"),
                "done"),
            node("done", WorkflowDefinition.END, config("message", "Finished."), ""));
        WorkflowRun run = new WorkflowEngine(new Recorder()).run(
            WorkflowDefinition.fromJson(json), new JSONObject());

        JSONObject step = run.steps().optJSONObject(0);
        check("every step is recorded with its node", step.optString("node").equals("think"));
        check("and its kind", step.optString("type").equals("llm"));
        check("and how it went", step.optString("outcome").equals("done"));
        check("and when", step.optLong("at") > 0);
        check("the whole run serialises", run.toJson().optString("status").equals("done"));

        // The point of the trace: a later run can pick the variables back up.
        WorkflowRun resumed = WorkflowRun.resume(run.toJson(), "run-2");
        check("a resumed run remembers what was known",
            resumed.text("answer").equals(run.text("answer")));
        check("and where it had got to", resumed.lastNode().equals("done"));

        Recorder host = new Recorder();
        WorkflowRun fromMiddle = new WorkflowEngine(host).run(
            WorkflowDefinition.fromJson(json), new JSONObject(), "done");
        check("a run can start from a checkpoint instead of the beginning",
            host.calls.isEmpty() && fromMiddle.ok());
    }

    private static void registry() {
        WorkflowRegistry registry = new WorkflowRegistry();
        JSONObject json = flow("acme.line",
            node("done", WorkflowDefinition.END, config("message", "Done."), ""));
        check("a good workflow is accepted", registry.register(json) == null);
        check("and listed", registry.has("acme.line"));
        check("installing it twice is refused",
            registry.register(json).contains("already installed"));

        JSONObject broken = flow("acme.broken",
            node("one", WorkflowDefinition.LLM, config("prompt", "x"), "nowhere"));
        check("a broken one is refused at install time", registry.register(broken) != null);
        check("never registered", !registry.has("acme.broken"));
        check("and the reason is kept", registry.refusals().containsKey("acme.broken"));
        check("the catalogue describes what is there",
            registry.describe().optJSONObject(0).optString("id").equals("acme.line"));
    }

    // --- helpers --------------------------------------------------------------

    private static String end(WorkflowDefinition workflow, String key, String value) {
        JSONObject input = new JSONObject();
        put(input, key, value);
        return new WorkflowEngine(new Recorder()).run(workflow, input).message();
    }

    private static int count(List<String> calls, String needle) {
        int total = 0;
        for (String call : calls) {
            if (call.startsWith(needle)) {
                total++;
            }
        }
        return total;
    }

    private static JSONObject flow(String id, Object... nodes) {
        return capped(id, 60, nodes);
    }

    private static JSONObject capped(String id, int maxSteps, Object... nodes) {
        JSONObject json = new JSONObject();
        put(json, "id", id);
        put(json, "name", id);
        put(json, "maxSteps", maxSteps);
        JSONArray array = new JSONArray();
        for (Object node : nodes) {
            array.put(node);
        }
        put(json, "nodes", array);
        return json;
    }

    private static JSONObject node(String id, String type, JSONObject config, String next) {
        return node(id, type, config, next, "", new String[0]);
    }

    private static JSONObject node(String id, String type, JSONObject config, String next,
                                   String body, String[] branches) {
        JSONObject json = new JSONObject();
        put(json, "id", id);
        put(json, "type", type);
        put(json, "config", config);
        put(json, "next", next);
        put(json, "body", body);
        JSONArray array = new JSONArray();
        for (String branch : branches) {
            array.put(branch);
        }
        put(json, "branches", array);
        return json;
    }

    /** A loop node: body is the fifth argument, so it gets its own helper. */
    private static JSONObject node(String id, String type, JSONObject config, String next,
                                   String body) {
        return node(id, type, config, next, body, new String[0]);
    }

    private static JSONObject branch(String id, String when, String whenTrue, String whenFalse) {
        JSONObject json = node(id, WorkflowDefinition.CONDITION, config("when", when), "");
        put(json, "whenTrue", whenTrue);
        put(json, "whenFalse", whenFalse);
        return json;
    }

    private static JSONObject tool(String tool, String path, String content) {
        JSONObject args = new JSONObject();
        put(args, "path", path);
        if (!content.isEmpty()) {
            put(args, "content", content);
        }
        JSONObject config = new JSONObject();
        put(config, "tool", tool);
        put(config, "args", args);
        return config;
    }

    private static JSONObject merge(JSONObject config, String key, Object value) {
        put(config, key, value);
        return config;
    }

    private static JSONObject config(String... pairs) {
        JSONObject json = new JSONObject();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            put(json, pairs[index], pairs[index + 1]);
        }
        return json;
    }

    private static void put(JSONObject json, String key, Object value) {
        try {
            json.put(key, value);
        } catch (Exception ignored) {
            // Nothing here is unserialisable.
        }
    }

    private static void check(String what, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  pass  " + what);
        } else {
            failed++;
            System.out.println("  FAIL  " + what);
        }
    }

    /** A host that does nothing but remember it was asked. */
    private static final class Recorder implements WorkflowHost {

        final List<String> calls = new ArrayList<>();
        final List<String> paths = new ArrayList<>();

        String answer = "an answer";
        String reply = "an reply";
        boolean approve = true;
        boolean toolFails;
        int stopAfter = -1;

        @Override
        public String think(String prompt) {
            calls.add("think:" + prompt);
            return answer;
        }

        @Override
        public ToolResult tool(String toolId, JSONObject args) {
            calls.add("tool:" + toolId);
            paths.add(args.optString("path", ""));
            return toolFails ? ToolResult.failed("no") : ToolResult.ok("did it");
        }

        @Override
        public boolean approve(String message, String consequence) {
            calls.add("approve:" + message);
            return approve;
        }

        @Override
        public String ask(String question) {
            calls.add("ask:" + question);
            return reply;
        }

        @Override
        public String subAgent(String agentId, String task) {
            calls.add("agent:" + agentId);
            return "done";
        }

        @Override
        public boolean pause(long millis) {
            calls.add("pause:" + millis);
            return true;
        }

        @Override
        public boolean stopped() {
            return stopAfter >= 0 && calls.size() >= stopAfter;
        }

        @Override
        public void event(JSONObject event) {
            // Nothing to show in a test.
        }
    }
}
