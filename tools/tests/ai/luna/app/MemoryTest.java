package ai.luna.app;

import ai.luna.builtin.Builtins;
import ai.luna.builtin.CoreSkills;
import ai.luna.contracts.MemoryKind;
import ai.luna.contracts.MemoryRecord;
import ai.luna.contracts.ToolContext;
import ai.luna.contracts.ToolProvider;
import ai.luna.contracts.Trace;
import ai.luna.runtime.EphemeralMemory;
import ai.luna.runtime.MemoryRegistry;
import ai.luna.runtime.SkillRegistry;
import ai.luna.runtime.SkillResolver;
import ai.luna.runtime.SystemPrompt;
import ai.luna.runtime.ToolRegistry;

import java.util.Arrays;
import java.util.List;

/**
 * Remembering, in five kinds that are not one thing.
 *
 * <p>What is being checked: that the kinds are actually separate, that working
 * memory is lost on purpose, that recall brings back the few things that bear
 * on what was said rather than everything, and that a full store drops what
 * matters least rather than what arrived first.
 */
public final class MemoryTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        kinds();
        routing();
        workingMemoryIsLost();
        recall();
        importance();
        pruning();
        theRecord();
        inThePrompt();

        System.out.println();
        if (failed > 0) {
            System.out.println(failed + " FAILED, " + passed + " passed");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static void kinds() {
        check("there are five kinds", MemoryKind.ALL.size() == 5);
        check("conversation is one", MemoryKind.isKind(MemoryKind.CONVERSATION));
        check("a made-up kind is not", !MemoryKind.isKind("vibes"));
        check("only working memory is meant to be lost",
            MemoryKind.transient_(MemoryKind.WORKING)
                && !MemoryKind.transient_(MemoryKind.LONG_TERM));
        check("every kind can be explained to a person",
            !MemoryKind.describe(MemoryKind.EXECUTION).isEmpty());
    }

    private static void routing() {
        MemoryRegistry registry = registry();
        check("working memory has a home", registry.holds(MemoryKind.WORKING));
        check("long-term memory has a different one",
            !registry.providerFor(MemoryKind.WORKING).id()
                .equals(registry.providerFor(MemoryKind.LONG_TERM).id()));

        registry.remember(MemoryKind.LONG_TERM, "luna", "They prefer short answers.", 90);
        registry.remember(MemoryKind.WORKING, "luna", "Reading notes.md now.", 20);
        check("each goes to its own place",
            registry.all(MemoryKind.LONG_TERM, "luna").size() == 1
                && registry.all(MemoryKind.WORKING, "luna").size() == 1);
        check("memory is per agent",
            registry.all(MemoryKind.LONG_TERM, "acme.reader").isEmpty());

        registry.remember("vibes", "luna", "Nothing holds this.", 50);
        check("a kind nobody holds is quietly not remembered",
            registry.all("vibes", "luna").isEmpty());
    }

    private static void workingMemoryIsLost() {
        MemoryRegistry registry = registry();
        registry.remember(MemoryKind.WORKING, "luna", "Halfway through the third file.", 60);
        registry.remember(MemoryKind.LONG_TERM, "luna", "Their folder is called Work.", 70);

        int dropped = registry.endOfRun("luna");
        check("the run's notes go when the run does", dropped == 1);
        check("working memory is empty", registry.all(MemoryKind.WORKING, "luna").isEmpty());
        check("what was learned is not",
            registry.all(MemoryKind.LONG_TERM, "luna").size() == 1);
    }

    private static void recall() {
        MemoryRegistry registry = registry();
        registry.remember(MemoryKind.KNOWLEDGE, "luna",
            "Invoices live in the Billing folder.", 60, "invoice");
        registry.remember(MemoryKind.KNOWLEDGE, "luna",
            "Photographs are in Camera Roll and are never edited.", 60, "photo");
        registry.remember(MemoryKind.LONG_TERM, "luna",
            "They always want file names in lower case.", 95);

        List<String> lines = registry.recallLines("where is the March invoice?", "luna", 5);
        check("what is about the subject comes back",
            lines.contains("Invoices live in the Billing folder."));
        check("what is not about it does not",
            !lines.contains("Photographs are in Camera Roll and are never edited."));
        check("something important enough comes back anyway",
            lines.contains("They always want file names in lower case."));

        List<MemoryRecord> limited = registry.recall("invoice", "luna",
            Arrays.asList(MemoryKind.KNOWLEDGE, MemoryKind.LONG_TERM), 1);
        check("recall respects the limit it was given", limited.size() == 1);
        check("and returns the best match first",
            limited.get(0).text.contains("Billing"));

        check("recalling about nothing brings back only what matters most",
            registry.recallLines("qqqq", "luna", 5).size() == 1);
    }

    private static void importance() {
        MemoryRegistry registry = registry();
        registry.remember(MemoryKind.KNOWLEDGE, "luna", "notes.md is the shopping list.", 10,
            "notes");
        registry.remember(MemoryKind.KNOWLEDGE, "luna",
            "notes.md is where the meeting notes go.", 90, "notes");
        List<MemoryRecord> found = registry.recall("notes", "luna",
            Arrays.asList(MemoryKind.KNOWLEDGE), 2);
        check("between two matches, the important one leads",
            found.get(0).text.contains("meeting notes"));
    }

    private static void pruning() {
        EphemeralMemory small = new EphemeralMemory(
            Arrays.asList(MemoryKind.WORKING), 10);
        MemoryRegistry registry = new MemoryRegistry().register(small);
        registry.remember(MemoryKind.WORKING, "luna", "This one matters.", 100);
        for (int index = 0; index < 30; index++) {
            registry.remember(MemoryKind.WORKING, "luna", "Passing note " + index, 10);
        }
        List<MemoryRecord> held = registry.all(MemoryKind.WORKING, "luna");
        check("a full store stays at its limit", held.size() == 10);
        boolean kept = false;
        for (MemoryRecord record : held) {
            if (record.text.equals("This one matters.")) {
                kept = true;
            }
        }
        check("and drops what matters least, not what came first", kept);
    }

    private static void theRecord() {
        MemoryRecord record = MemoryRecord.of(MemoryKind.LONG_TERM, "luna",
            "They work in British English.").tagged("style").weighing(80);
        check("a record knows its kind", record.kind.equals(MemoryKind.LONG_TERM));
        check("and its weight", record.importance == 80);
        check("weight is clamped", record.weighing(500).importance == 100);
        check("its terms include its tags", record.terms().contains("style"));
        check("and its own words", record.terms().contains("british"));
        check("but not the short ones", !record.terms().contains("in"));

        try {
            MemoryRecord copy = MemoryRecord.fromJson(record.toJson());
            check("a record survives a round trip", copy.text.equals(record.text));
            check("with its tags", copy.tags.equals(record.tags));
            check("and its time", copy.at == record.at);
        } catch (Exception error) {
            check("a record survives a round trip: " + error, false);
        }

        MemoryRegistry registry = registry();
        registry.remember(MemoryKind.LONG_TERM, "luna", "Forget this one.", 50);
        String id = registry.all(MemoryKind.LONG_TERM, "luna").get(0).id;
        check("a single memory can be forgotten", registry.forget(id));
        check("and is gone", registry.all(MemoryKind.LONG_TERM, "luna").isEmpty());
        check("forgetting something absent is honest", !registry.forget(id));

        registry.remember(MemoryKind.KNOWLEDGE, "luna", "One.", 50);
        registry.remember(MemoryKind.KNOWLEDGE, "luna", "Two.", 50);
        check("a whole kind can be cleared", registry.clear(MemoryKind.KNOWLEDGE, "luna") == 2);
        check("the catalogue counts what is held",
            registry.describe("luna").length() == 5);
    }

    private static void inThePrompt() {
        MemoryRegistry registry = registry();
        registry.remember(MemoryKind.KNOWLEDGE, "luna",
            "Invoices live in the Billing folder.", 60, "invoice");

        SystemPrompt prompt = new SystemPrompt(tools(),
            new SkillRegistry().register(new CoreSkills()), new SkillResolver());
        String text = prompt.build(context(), "find the March invoice", "Work", false, null,
            registry.recallLines("find the March invoice", "luna", 5));
        check("what is remembered reaches the prompt",
            text.contains("Invoices live in the Billing folder."));
        check("under a heading a model can use", text.contains("You already know:"));

        String without = prompt.build(context(), "find the March invoice", "Work", false, null);
        check("and nothing is added when nothing is remembered",
            !without.contains("You already know:"));
    }

    // --- helpers --------------------------------------------------------------

    private static MemoryRegistry registry() {
        return new MemoryRegistry()
            .register(new EphemeralMemory())
            .register(new EphemeralMemory("memory.kept", Arrays.asList(
                MemoryKind.LONG_TERM, MemoryKind.KNOWLEDGE, MemoryKind.EXECUTION), 500));
    }

    private static ToolRegistry tools() {
        ToolRegistry registry = new ToolRegistry();
        for (ToolProvider provider : Builtins.all()) {
            registry.register(provider);
        }
        return registry;
    }

    private static ToolContext context() {
        return new ToolContext("luna", "core", new Fakes.FakeStorage(), new Fakes.FakeBrowser(),
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
