package ai.luna.app;

import ai.luna.builtin.Builtins;
import ai.luna.builtin.CoreSkills;
import ai.luna.builtin.LunaAgent;
import ai.luna.contracts.AgentDefinition;
import ai.luna.contracts.SkillDefinition;
import ai.luna.contracts.ToolContext;
import ai.luna.contracts.ToolProvider;
import ai.luna.contracts.Trace;
import ai.luna.runtime.AgentManager;
import ai.luna.runtime.AgentRegistry;
import ai.luna.runtime.SkillRegistry;
import ai.luna.runtime.SkillResolver;
import ai.luna.runtime.SystemPrompt;
import ai.luna.runtime.ToolRegistry;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Agents as a plural.
 *
 * <p>The thing being checked is that Luna is now one entry in a registry — that
 * a second agent can exist, can be narrower than her, and that being narrower
 * is the only direction a definition can move in. An agent asking for more than
 * the phone offers must still get nothing.
 */
public final class AgentsTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        lunaIsAnEntry();
        installing();
        serialisation();
        switching();
        narrowing();
        cannotWiden();
        budgets();
        promptForOneAgent();
        goalSurvivesTrimming();
        cacheablePrefix();

        System.out.println();
        if (failed > 0) {
            System.out.println(failed + " FAILED, " + passed + " passed");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static void lunaIsAnEntry() {
        AgentRegistry registry = new AgentRegistry().register(LunaAgent.DEFINITION);
        check("Luna is registered", registry.has("luna"));
        check("she is the fallback", registry.fallback().id.equals("luna"));
        check("she is marked as shipped", registry.fallback().builtIn);
        check("she asks for every tool", LunaAgent.DEFINITION.allows("delete_file"));
        check("and every skill", LunaAgent.DEFINITION.knows("core.web"));
        check("the catalogue is data", registry.describe().length() == 1);
    }

    private static void installing() {
        AgentRegistry registry = new AgentRegistry().register(LunaAgent.DEFINITION);
        registry.registerJson(reader());
        check("an installed agent joins the list", registry.has("acme.reader"));
        check("Luna is still the fallback", registry.fallback().id.equals("luna"));
        check("an installed agent is not built in", !registry.get("acme.reader").builtIn);

        JSONObject liar = reader();
        try {
            liar.put("id", "acme.liar");
            liar.put("builtIn", true);
        } catch (Exception ignored) {
            // Strings only.
        }
        registry.registerJson(liar);
        check("an agent cannot declare itself shipped with the runtime",
            !registry.has("acme.liar"));

        registry.registerJson(new JSONObject());
        check("an agent with no id is not installed", registry.all().size() == 2);

        JSONObject impostor = reader();
        try {
            impostor.put("id", "luna");
            impostor.put("builtIn", false);
        } catch (Exception ignored) {
            // Strings only.
        }
        registry.registerJson(impostor);
        check("nothing can take Luna's id", registry.get("luna").builtIn);
    }

    private static void serialisation() {
        try {
            AgentDefinition copy = AgentDefinition.fromJson(LunaAgent.DEFINITION.toJson());
            check("an agent survives a round trip", copy.id.equals("luna"));
            check("with its tool list", copy.tools.equals(LunaAgent.DEFINITION.tools));
            check("with its version", copy.version.equals("1.0.0"));
        } catch (Exception error) {
            check("an agent survives a round trip: " + error, false);
        }
    }

    private static void switching() {
        AgentManager manager = manager();
        check("Luna runs when nothing was chosen", manager.activeId().equals("luna"));
        check("switching to an unknown agent fails", !manager.activate("nobody"));
        check("and changes nothing", manager.activeId().equals("luna"));
        check("switching to an installed one works", manager.activate("acme.reader"));
        check("and takes effect", manager.activeId().equals("acme.reader"));
    }

    private static void narrowing() {
        AgentManager manager = manager();
        ToolContext context = context(true, true);
        check("Luna gets everything the phone offers",
            manager.toolIds(context).size() == 15);

        manager.activate("acme.reader");
        List<String> narrowed = manager.toolIds(context);
        check("a reader gets its three tools", narrowed.size() == 3);
        check("reading is among them", narrowed.contains("read_file"));
        check("writing is not", !narrowed.contains("write_file"));
        check("and it may not call one anyway", !manager.canUse("write_file"));
        check("it may call the ones it has", manager.canUse("read_file"));
        check("the prompt lines are narrowed too", manager.promptLines(context).size() == 3);

        List<String> skills = ids(manager.skills());
        check("it keeps the skill it was given", skills.contains("core.identity"));
        check("and not the ones it was not", !skills.contains("core.web"));
    }

    /** The rule that has to hold once anybody can write a definition. */
    private static void cannotWiden() {
        AgentRegistry registry = new AgentRegistry().register(LunaAgent.DEFINITION);
        JSONObject greedy = new JSONObject();
        try {
            greedy.put("id", "acme.greedy");
            greedy.put("name", "Greedy");
            greedy.put("tools", new org.json.JSONArray(
                java.util.Arrays.asList("read_file", "shell_exec", "deploy_vps")));
            greedy.put("skills", new org.json.JSONArray(java.util.Arrays.asList("*")));
        } catch (Exception ignored) {
            // Strings only.
        }
        registry.registerJson(greedy);

        AgentManager manager = new AgentManager(registry, builtinTools(), coreSkills());
        manager.activate("acme.greedy");
        check("asking for a shell does not produce one", !manager.canUse("shell_exec"));
        check("nor a deployment", !manager.canUse("deploy_vps"));
        check("the tool it may actually have still works", manager.canUse("read_file"));
        check("only the real tool is offered", manager.toolIds(context(true, true)).size() == 1);
        check("and the invented ones never reach the prompt",
            !manager.promptLines(context(true, true)).toString().contains("shell_exec"));
    }

    private static void budgets() {
        AgentManager manager = manager();
        check("Luna takes the app's limit", manager.steps(12) == 12);

        manager.activate("acme.reader");
        check("an agent may set itself a smaller limit", manager.steps(12) == 4);
        check("but never a larger one", manager.steps(2) == 2);
        check("the same for time", manager.seconds(600) == 60);
        check("and a limit of zero means the app decides", manager.seconds(30) == 30);
    }

    private static void promptForOneAgent() {
        AgentManager manager = manager();
        manager.activate("acme.reader");
        SystemPrompt prompt = new SystemPrompt(builtinTools(), coreSkills(), new SkillResolver(),
            manager);
        String text = prompt.build(context(true, true), "what is in my notes?", "Documents",
            false, null);
        check("the prompt offers only the agent's tools", text.contains("\"tool\":\"read_file\""));
        check("and not the ones it was denied", !text.contains("\"tool\":\"delete_file\""));
        check("the agent's own instructions are included",
            text.contains("You only ever read."));
        check("they come after the shared rules",
            text.indexOf("Never claim you did something") < text.indexOf("You only ever read."));
    }

    /**
     * The request is restated in the system prompt, not only in the history.
     *
     * <p>History is trimmed oldest-first, so on a long job the message that
     * started the work is the first thing dropped -- and an agent that has
     * forgotten the goal keeps going anyway. The system prompt is rebuilt
     * every turn and never trimmed, which makes it the durable place for it.
     */
    private static void goalSurvivesTrimming() {
        SystemPrompt prompt = new SystemPrompt(builtinTools(), coreSkills(), new SkillResolver(),
            manager());
        String text = prompt.build(context(true, true),
            "rename every screenshot to the date it was taken", "Pictures", false, null);
        check("the prompt restates the job",
            text.contains("rename every screenshot to the date it was taken"));
        check("and says to keep at it", text.contains("until it is done"));

        // A blank message must not leave a heading with nothing under it.
        String empty = prompt.build(context(true, true), "   ", "Pictures", false, null);
        check("an empty request adds no heading", !empty.contains("What you were asked to do"));

        // Long enough to matter: the goal repeats every turn, so it is capped.
        StringBuilder longAsk = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longAsk.append("tidy the folder ");
        }
        String capped = prompt.build(context(true, true), longAsk.toString(), "Pictures",
            false, null);
        int start = capped.indexOf("What you were asked to do");
        int end = capped.indexOf("Keep doing this", start);
        check("a rambling request is clamped", end - start < 600);
        check("but still says what it was", capped.contains("tidy the folder"));
    }

    /**
     * Two calls that differ only in the situation share a long opening.
     *
     * <p>Groq bills the longest prefix it has already seen at half price, and
     * cached tokens are not counted against the per-minute limit -- but both
     * stop at the first byte that differs. The date used to be the third line
     * of the prompt, so at midnight, or on a folder change, or simply because
     * the request was different, the discount was lost on everything after it.
     * The rules and the tool list are identical on every call, so they belong
     * above anything situational.
     */
    private static void cacheablePrefix() {
        SystemPrompt prompt = new SystemPrompt(builtinTools(), coreSkills(), new SkillResolver(),
            manager());
        String one = prompt.build(context(true, true), "tidy my downloads", "Downloads",
            false, null);
        String two = prompt.build(context(true, true), "find the March invoice", "Work",
            true, null);

        int shared = 0;
        while (shared < one.length() && shared < two.length()
            && one.charAt(shared) == two.charAt(shared)) {
            shared++;
        }
        // The whole static half must match: rules, tool list, the lot.
        check("two unrelated calls share a long prefix", shared > 2000);
        check("the shared part covers the tool list",
            one.substring(0, shared).contains("\"tool\":\"read_file\""));
        // The label "Folder granted: " is itself identical on every call, so it
        // sits legitimately inside the shared prefix -- it is the value after
        // it that diverges. What must not be shared is a situational *value*.
        check("no situational value is inside it",
            !one.substring(0, shared).contains("Downloads")
                && !one.substring(0, shared).contains("tidy my downloads"));
        check("the divergence is at the folder value, not earlier",
            shared > one.indexOf("Folder granted"));

        // The order itself: static above, situational below.
        check("the tool list comes before the folder",
            one.indexOf("To use a tool") < one.indexOf("Folder granted"));
        check("and before the request",
            one.indexOf("To use a tool") < one.indexOf("What you were asked to do"));
        check("the date is below the rules",
            one.indexOf("Never claim you did something") < one.indexOf("Today is"));

        // Same inputs must still be byte-identical, or nothing ever caches.
        check("the same call twice is identical",
            prompt.build(context(true, true), "tidy my downloads", "Downloads", false, null)
                .equals(one));
        // And the situational parts are still actually present.
        check("the folder is still stated", one.contains("Folder granted: Downloads"));
        check("the mode is still stated", two.contains("Mode: unattended"));
    }

    // --- helpers --------------------------------------------------------------

    /** An agent that can read and nothing else, with a budget of its own. */
    private static JSONObject reader() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", "acme.reader");
            json.put("name", "Reader");
            json.put("description", "Reads, summarises, changes nothing.");
            json.put("instructions", "You only ever read. If a job needs a change, say so.");
            json.put("tools", new org.json.JSONArray(
                java.util.Arrays.asList("list_files", "read_file", "respond")));
            json.put("skills", new org.json.JSONArray(
                java.util.Arrays.asList("core.identity", "core.restraint", "core.files",
                    "core.reporting")));
            json.put("maxSteps", 4);
            json.put("maxSeconds", 60);
        } catch (Exception ignored) {
            // Strings only.
        }
        return json;
    }

    private static AgentManager manager() {
        AgentRegistry registry = new AgentRegistry().register(LunaAgent.DEFINITION);
        registry.registerJson(reader());
        return new AgentManager(registry, builtinTools(), coreSkills());
    }

    private static SkillRegistry coreSkills() {
        return new SkillRegistry().register(new CoreSkills());
    }

    private static ToolRegistry builtinTools() {
        ToolRegistry registry = new ToolRegistry();
        for (ToolProvider provider : Builtins.all()) {
            registry.register(provider);
        }
        return registry;
    }

    private static List<String> ids(List<SkillDefinition> skills) {
        List<String> out = new ArrayList<>();
        for (SkillDefinition skill : skills) {
            out.add(skill.id);
        }
        return out;
    }

    private static ToolContext context(boolean folder, boolean browser) {
        return new ToolContext("luna", "core",
            folder ? new Fakes.FakeStorage() : null,
            browser ? new Fakes.FakeBrowser() : null,
            null, Trace.SILENT, "android");
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
}
