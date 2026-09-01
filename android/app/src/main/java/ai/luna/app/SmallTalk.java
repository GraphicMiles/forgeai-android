package ai.luna.app;

import java.util.Locale;

/**
 * Is this a job, or is somebody saying hello?
 *
 * <p>A small model that is shown a list of tools will use one. Asked "hi", a
 * 0.5B model will happily open a browser at a made-up address, wait for
 * approval it should never have asked for, and dead-end the whole turn. The
 * cheapest fix is not to show it the tools at all when the message plainly does
 * not need them.
 *
 * <p>The test is deliberately conservative. Anything long, anything with a
 * question about a file or a page, anything with an address in it, is a job.
 * Getting this wrong in one direction costs a wasted step; in the other it
 * costs an answer, so it only fires on things that are unmistakably chat.
 */
public final class SmallTalk {

    /** Complete messages that never need a tool. */
    private static final String[] EXACT = {
        "hi", "hii", "hiya", "hello", "helo", "hey", "heyy", "yo", "sup", "howdy",
        "good morning", "good afternoon", "good evening", "morning", "evening",
        "thanks", "thank you", "thx", "ty", "cheers", "nice", "cool", "great",
        "ok", "okay", "k", "alright", "got it", "understood", "no", "nope",
        "yes", "yeah", "yep", "test", "testing", "ping", "luna", "hello luna",
        "hi luna", "hey luna", "are you there", "you there", "still there",
        "how are you", "how are you doing", "whats up", "what up",
        "who are you", "what are you", "what can you do", "what do you do",
        "help", "hlep", "helpp", "helllo", "helloo", "hallo", "allo",
        "gud morning", "good mornin",
        "bye", "goodbye", "good night", "night",
    };

    private SmallTalk() {
    }

    /** True when the message is a greeting or a question about Luna herself. */
    public static boolean matches(String raw) {
        String text = normalise(raw);
        if (text.isEmpty()) {
            return false;
        }
        // Anything with an address, a path or a file extension is a job.
        String original = raw == null ? "" : raw.toLowerCase(Locale.US);
        if (original.contains("http") || original.contains("www.") || original.contains("/")
            || original.contains(".com") || original.contains(".md") || original.contains(".txt")) {
            return false;
        }
        if (words(text) > 6) {
            return false;
        }
        for (String phrase : EXACT) {
            if (text.equals(phrase)) {
                return true;
            }
            // One typo in a single greeting word still reads as a greeting.
            if (words(text) == 1 && words(phrase) == 1 && oneAway(text, phrase)) {
                return true;
            }
        }
        // "hi there", "hey luna, morning" — an opener plus pleasantries only.
        for (String phrase : EXACT) {
            if (text.startsWith(phrase + " ") && words(text) <= 4 && !hasVerbOfWork(text)) {
                return true;
            }
        }
        return false;
    }

    /** True when two single words differ by at most one character. */
    private static boolean oneAway(String left, String right) {
        if (Math.abs(left.length() - right.length()) > 1) {
            return false;
        }
        int edits = 0;
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            if (left.charAt(i) == right.charAt(j)) {
                i++;
                j++;
                continue;
            }
            edits++;
            if (edits > 1) {
                return false;
            }
            if (left.length() > right.length()) {
                i++;
            } else if (right.length() > left.length()) {
                j++;
            } else {
                i++;
                j++;
            }
        }
        return edits + (left.length() - i) + (right.length() - j) <= 1;
    }

    /** Words that mean a job is being described, however short the sentence. */
    private static boolean hasVerbOfWork(String text) {
        String[] verbs = {"read", "write", "open", "list", "find", "search", "make", "create",
            "delete", "rename", "check", "fix", "build", "summar", "explain", "download",
            "show", "get", "fetch", "look", "tell me about", "count", "sort", "rename"};
        for (String verb : verbs) {
            if (text.contains(verb)) {
                return true;
            }
        }
        return false;
    }

    private static int words(String text) {
        return text.isEmpty() ? 0 : text.split(" ").length;
    }

    /** Lower case, letters and spaces only, punctuation and emoji dropped. */
    static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.toLowerCase(Locale.US).replaceAll("[^a-z ]", " ");
        return text.replaceAll("\\s+", " ").trim();
    }
}
