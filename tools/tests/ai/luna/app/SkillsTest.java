package ai.luna.app;

import ai.luna.builtin.Builtins;
import ai.luna.builtin.CoreSkills;
import ai.luna.contracts.SkillDefinition;
import ai.luna.contracts.ToolContext;
import ai.luna.contracts.ToolProvider;
import ai.luna.contracts.Trace;
import ai.luna.runtime.SkillRegistry;
import ai.luna.runtime.SkillResolver;
import ai.luna.runtime.SystemPrompt;
import ai.luna.runtime.ToolRegistry;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The skill system, and the prompt it produces.
 *
 * <p>The point of these checks is that Luna's competence survived being turned
 * into data: the same rules reach the model, they reach it in the same order,
 * and the ones that do not apply to this device stay out.
 */
public final class SkillsTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        registration();
        serialisation();
        fitting();
        resolution();
        theWorkingPrompt();
        theGreetingPrompt();
        installedSkills();
        budget();

        System.out.println();
        if (failed > 0) {
            System.out.println(failed + " FAILED, " + passed + " passed");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static void registration() {
        SkillRegistry registry = core();
        check("the core skills are registered", registry.all().size() == 7);
        check("identity is one of them", registry.has("core.identity"));
        check("every skill is credited to core",
            registry.get("core.web").providerId.equals("core"));
        check("a skill can be switched off",
            !registry.disable(Arrays.asList("core.web")).isEnabled("core.web"));
        check("switching one off leaves the rest", registry.enabled().size() == 6);
        check("but it is still known", registry.has("core.web"));
        check("the catalogue says which are on",
            registry.describe().optJSONObject(0).optBoolean("enabled"));
    }

    private static void serialisation() {
        SkillDefinition original = CoreSkills.FILES;
        try {
            SkillDefinition copy = SkillDefinition.fromJson(original.toJson());
            check("a skill survives a round trip", copy.id.equals(original.id));
            check("with its instructions", copy.instructions.equals(original.instructions));
            check("with its tools", copy.tools.equals(original.tools));
            check("with what it requires", copy.requires.equals(original.requires));
            check("with its place in the order", copy.order == original.order);
        } catch (Exception error) {
            check("a skill survives a round trip: " + error, false);
        }
    }

    private static void fitting() {
        check("the folder skill needs a folder", !CoreSkills.FILES.fits(false, true));
        check("and is fine when there is one", CoreSkills.FILES.fits(true, false));
        check("the no-folder skill is the other way round",
            CoreSkills.NO_FOLDER.fits(false, false) && !CoreSkills.NO_FOLDER.fits(true, false));
        check("the two are never both true",
            CoreSkills.FILES.fits(true, true) != CoreSkills.NO_FOLDER.fits(true, true));
        check("identity always fits", CoreSkills.IDENTITY.fits(false, false));
        check("an always-on skill is triggered by anything",
            CoreSkills.RESTRAINT.triggeredBy("hi"));
    }

    private static void resolution() {
        SkillResolver resolver = new SkillResolver();
        ToolRegistry tools = builtinTools();

        List<String> full = ids(resolver.resolve(core().enabled(), context(true, true), tools,
            "tidy up my notes folder"));
        check("with a folder, the folder skill is in", full.contains("core.files"));
        check("and the no-folder skill is not", !full.contains("core.no-folder"));
        check("the web skill comes with the browser", full.contains("core.web"));
        check("asking comes with ask_user", full.contains("core.asking"));

        List<String> bare = ids(resolver.resolve(core().enabled(), context(false, false), tools,
            "tidy up my notes folder"));
        check("with nothing granted, the no-folder skill is in",
            bare.contains("core.no-folder"));
        check("the folder skill is out", !bare.contains("core.files"));
        check("the web skill is out too", !bare.contains("core.web"));
        check("identity and restraint survive everything",
            bare.contains("core.identity") && bare.contains("core.restraint"));

        List<String> off = ids(resolver.resolve(
            core().disable(Arrays.asList("core.files")).enabled(),
            context(true, true), tools, "read a file"));
        check("a disabled skill never reaches the prompt", !off.contains("core.files"));
    }

    private static void theWorkingPrompt() {
        SystemPrompt prompt = new SystemPrompt(builtinTools(), core(), new SkillResolver());
        String full = prompt.build(context(true, true), "sort out my notes", "Documents", false,
            null);
        check("the prompt says who Luna is", full.contains("local utility agent"));
        check("restraint comes before the tool list",
            full.indexOf("Most messages need no tool")
                < full.indexOf("reply with one JSON object"));
        check("identity comes before the folder line",
            full.indexOf("local utility agent") < full.indexOf("Folder granted:"));
        check("the folder is named", full.contains("Folder granted: Documents"));
        check("the mode is stated", full.contains("Mode: ask before acting"));
        check("the honesty rule is there",
            full.contains("Never claim you did something a tool result does not show"));
        check("the tool lines come from the registry", full.contains("\"tool\":\"read_file\""));
        check("a persona is appended when the model has one",
            prompt.build(context(true, true), "hi", "Documents", false, "You are terse.")
                .endsWith("You are terse.\n"));

        String bare = prompt.build(context(false, false), "sort out my notes", "", true, null);
        check("with no folder the prompt says so", bare.contains("No folder has been granted"));
        check("and never mentions a file tool", !bare.contains("\"tool\":\"write_file\""));
        check("nor a page tool", !bare.contains("\"tool\":\"open_page\""));
        check("unattended mode is stated", bare.contains("Mode: unattended"));
        check("the folderless prompt is much shorter", bare.length() < full.length());
    }

    private static void theGreetingPrompt() {
        SystemPrompt prompt = new SystemPrompt(builtinTools(), core(), new SkillResolver());
        String greeting = prompt.conversational(CoreSkills.SMALL_TALK, null);
        check("a greeting gets no tool list", !greeting.contains("\"tool\":"));
        check("and is told not to mention tools", greeting.contains("Do not mention"));
        check("and not to invent work", greeting.contains("you have done nothing yet"));
        check("it is short", greeting.length() < 700);
    }

    /** A plugin ships knowledge as JSON. This is that path, end to end. */
    private static void installedSkills() {
        SkillRegistry registry = core();
        JSONObject json = new JSONObject();
        try {
            json.put("id", "acme.invoices");
            json.put("name", "Invoices");
            json.put("instructions", "Invoices live in the Billing folder, named by month.");
            json.put("triggers", new org.json.JSONArray(Arrays.asList("invoice", "billing")));
            json.put("requires", new org.json.JSONArray(Arrays.asList("workspace")));
            json.put("order", 60);
        } catch (Exception ignored) {
            // Strings only.
        }
        registry.addJson(json, "acme");
        check("an installed skill is registered", registry.has("acme.invoices"));
        check("and credited to the plugin, not to core",
            registry.get("acme.invoices").providerId.equals("acme"));

        SystemPrompt prompt = new SystemPrompt(builtinTools(), registry, new SkillResolver());
        String about = prompt.build(context(true, true), "where is last month's invoice?",
            "Work", false, null);
        check("it appears when the message is about it", about.contains("Invoices live in"));

        String other = prompt.build(context(true, true), "what time is it", "Work", false, null);
        check("and stays out when it is not", !other.contains("Invoices live in"));

        String noFolder = prompt.build(context(false, false), "where is last month's invoice?",
            "", false, null);
        check("it never appears without the folder it needs",
            !noFolder.contains("Invoices live in"));

        registry.addJson(json, "impostor");
        check("a second install cannot overwrite the first",
            registry.get("acme.invoices").providerId.equals("acme"));

        JSONObject empty = new JSONObject();
        registry.addJson(empty, "acme");
        check("a skill with no instructions is not installed", registry.all().size() == 8);
    }

    /** Context is finite, and an always-on skill outranks a chatty one. */
    private static void budget() {
        SkillRegistry registry = core();
        StringBuilder long_ = new StringBuilder();
        for (int index = 0; index < 200; index++) {
            long_.append("This skill goes on and on about nothing in particular. ");
        }
        registry.add(SkillDefinition.of("acme.verbose", "Verbose")
            .says(long_.toString())
            .always()
            .order(20)
            .build());
        registry.add(SkillDefinition.of("acme.quiet", "Quiet")
            .says("A short and useful thing to know about invoices.")
            .triggers("invoice")
            .order(70)
            .build());

        List<String> chosen = ids(new SkillResolver(500).resolve(registry.enabled(),
            context(true, true), builtinTools(), "invoice"));
        check("an always-on skill is never cut", chosen.contains("acme.verbose"));
        check("a triggered one is cut once the budget is gone",
            !chosen.contains("acme.quiet"));
        check("the core rules still make it in", chosen.contains("core.identity"));
    }

    // --- helpers --------------------------------------------------------------

    private static SkillRegistry core() {
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
