package ai.luna.contracts;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The five kinds of remembering, which are not one thing.
 *
 * <p>Today Luna has exactly one memory: the transcript. That single list is
 * asked to be the conversation, the scratch pad for the job in hand, what she
 * has learned about the person, what she knows about their files, and the
 * record of what she did — and it is bad at four of those. They differ in how
 * long they should last, what they cost in context, and what it means to delete
 * one.
 */
public final class MemoryKind {

    /** What was said. Lives with the chat; ends with the chat. */
    public static final String CONVERSATION = "conversation";

    /** Notes for the job in hand. Dies at the end of the run, deliberately. */
    public static final String WORKING = "working";

    /** What the person told Luna about themselves. Survives everything. */
    public static final String LONG_TERM = "long_term";

    /** Facts about the workspace: where things live, how they are named. */
    public static final String KNOWLEDGE = "knowledge";

    /** What was actually done, and how it went. The audit trail. */
    public static final String EXECUTION = "execution";

    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
        CONVERSATION, WORKING, LONG_TERM, KNOWLEDGE, EXECUTION));

    public static boolean isKind(String kind) {
        return ALL.contains(kind);
    }

    /** One sentence, for the screen where a person clears one of these. */
    public static String describe(String kind) {
        if (CONVERSATION.equals(kind)) {
            return "What was said in this chat";
        }
        if (WORKING.equals(kind)) {
            return "Notes Luna is keeping for the job she is doing now";
        }
        if (LONG_TERM.equals(kind)) {
            return "What you have told Luna about you and how you work";
        }
        if (KNOWLEDGE.equals(kind)) {
            return "What Luna has worked out about your folder";
        }
        if (EXECUTION.equals(kind)) {
            return "The record of what Luna has actually done";
        }
        return kind;
    }

    /** True when this kind should not survive the run that made it. */
    public static boolean transient_(String kind) {
        return WORKING.equals(kind);
    }

    private MemoryKind() {
    }
}
