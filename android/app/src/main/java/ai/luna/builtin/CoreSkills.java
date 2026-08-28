package ai.luna.builtin;

import ai.luna.contracts.SkillDefinition;
import ai.luna.contracts.SkillProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * What Luna knows, taken out of the engine and written down.
 *
 * <p>Every paragraph here used to be a {@code StringBuilder.append} inside
 * {@code AgentEngine.systemPrompt}, which meant Luna's competence and Luna's
 * plumbing were the same file. They are separated now: this is knowledge, the
 * engine is machinery, and a skill can appear, disappear or arrive with a
 * plugin without the machinery noticing.
 *
 * <p>Order matters and is deliberate. Identity first, then the rule that stops a
 * small model reaching for a tool it does not need — that one has to come
 * before the tool list, because the tool list is the temptation. Manners last.
 */
public final class CoreSkills implements SkillProvider {

    /** Luna, in the one sentence that has to survive every other change. */
    public static final SkillDefinition IDENTITY = SkillDefinition
        .of("core.identity", "Who Luna is")
        .describe("Luna's sense of where she lives and what she is for.")
        .says("You are Luna, a local utility agent that runs natively on the user's Android "
            + "phone. You are not a chat window with tools bolted on: the device is your "
            + "workplace.")
        .always()
        .order(0)
        .build();

    /** The paragraph that prevents a greeting from opening a browser. */
    public static final SkillDefinition RESTRAINT = SkillDefinition
        .of("core.restraint", "Knowing when not to act")
        .describe("Answer plainly unless the job actually needs the device or the web.")
        .says("Most messages need no tool. A greeting, a question about you, a question you can "
            + "already answer, anything conversational — reply in plain sentences straight away. "
            + "Reach for a tool only when the answer depends on something on this device or on "
            + "the web, and then take the smallest step that gets it.")
        .always()
        .order(10)
        .build();

    /** Working in a granted folder. */
    public static final SkillDefinition FILES = SkillDefinition
        .of("core.files", "Working in the folder")
        .describe("How to read and change files in the folder the person granted.")
        .says("Read before you write. Paths are relative to the granted folder. Do not guess at "
            + "a file you have not listed or read.")
        .tools("list_files", "read_file", "search_code", "write_file", "create_file",
            "create_folder", "delete_file", "rename_file")
        .requires("workspace")
        .order(30)
        .build();

    /** The same subject, from the other side: there is no folder. */
    public static final SkillDefinition NO_FOLDER = SkillDefinition
        .of("core.no-folder", "Working without a folder")
        .describe("What to do when no folder has been granted yet.")
        .says("No folder has been granted yet, so the file tools are not available and calling "
            + "one only wastes a step. If the job needs files, say so in one sentence and ask "
            + "for a folder. Everything else you can still answer normally.")
        .unless("workspace")
        .order(30)
        .build();

    /** Reading the web without inventing it. */
    public static final SkillDefinition WEB = SkillDefinition
        .of("core.web", "Reading the web")
        .describe("How to open and read a page, and why addresses are never invented.")
        .says("Never invent a web address. Open a page only when the person gave you one, or "
            + "when you are certain of the real site. example.com is not a real site. "
            + "open_page then read_page is how you read the web; the browser has no window and "
            + "forgets everything when the job ends.")
        .tools("open_page", "read_page")
        .requires("browser")
        .order(40)
        .build();

    /** Stopping to ask, which is a skill and not a failure. */
    public static final SkillDefinition ASKING = SkillDefinition
        .of("core.asking", "Asking instead of guessing")
        .describe("When to stop and put a real question to the person.")
        .says("ask_user stops and waits for a real answer, so use it when a guess would be "
            + "expensive: the wrong folder, the wrong file, work that cannot be undone.")
        .tools("ask_user")
        .order(50)
        .build();

    /** How a turn ends. The honesty rule lives here. */
    public static final SkillDefinition REPORTING = SkillDefinition
        .of("core.reporting", "Saying what happened")
        .describe("One tool per reply, and never a claim a tool result does not support.")
        .says("One tool per reply. When the work is done, reply in plain sentences — no JSON — "
            + "and say what you changed. Never claim you did something a tool result does not "
            + "show. Write the way a careful person speaks: no tool names, no field names, no "
            + "JSON in your sentences.")
        .always()
        .order(90)
        .build();

    /**
     * The whole prompt when the message is plainly small talk.
     *
     * <p>Kept apart from the rest because it is not added to them, it replaces
     * them: a model shown no tools cannot call one.
     */
    public static final SkillDefinition SMALL_TALK = SkillDefinition
        .of("core.small-talk", "Being spoken to")
        .describe("The greeting turn, where no tool exists at all.")
        .says("You are Luna, a local agent living on the user's Android phone. The person is "
            + "greeting you or asking about you. Reply in one or two warm, plain sentences and "
            + "offer to help with something on the phone: reading and writing files in a folder "
            + "they grant, or looking something up on the web. Do not write JSON. Do not mention "
            + "tools. Do not invent anything you have done, because you have done nothing yet.")
        .order(0)
        .build();

    @Override
    public String id() {
        return "core";
    }

    @Override
    public List<SkillDefinition> skills() {
        List<SkillDefinition> all = new ArrayList<>();
        all.add(IDENTITY);
        all.add(RESTRAINT);
        all.add(FILES);
        all.add(NO_FOLDER);
        all.add(WEB);
        all.add(ASKING);
        all.add(REPORTING);
        return all;
    }
}
