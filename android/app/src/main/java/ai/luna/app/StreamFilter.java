package ai.luna.app;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Decides whether a streamed reply is for the person or for the machine, and
 * only lets the first kind through to the chat.
 *
 * <p>A tool call is one JSON object, and a model often writes a short lead-in
 * before it ("Need web search. {@code {"tool":"search_web",...}}"). Neither the
 * JSON nor its lead-in may ever be typed into the thread, so the stream is
 * held back from the start of the trailing sentence and released only once it
 * is clearly prose. If a tool call turns up instead, everything held is dropped
 * with it. {@link #finish()} releases whatever is still held when the stream
 * ends, so a genuine answer is never lost — its last sentence simply appears a
 * moment after the stream closes.
 */
public final class StreamFilter {

    /** How much unpunctuated text is held before it streams regardless. */
    private static final int MAX_HOLD = 200;

    private final StringBuilder held = new StringBuilder();
    private boolean suppress;

    /** A streamed piece goes in; the piece safe to show comes out. */
    public String filter(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return "";
        }
        if (suppress) {
            return "";
        }
        String seen = held.toString() + chunk;

        if (toolCallStart(seen) >= 0) {
            // The reply is (or has become) a tool call. Nothing more is shown:
            // the lead-in that was held back is dropped along with the JSON.
            suppress = true;
            held.setLength(0);
            return "";
        }

        int cut = cutAfterBoundary(seen);
        String release = seen.substring(0, cut);
        held.setLength(0);
        held.append(seen.substring(cut));
        return release;
    }

    /** Anything still held when the stream ends, if it was not a tool call. */
    public String finish() {
        if (suppress) {
            return "";
        }
        String rest = held.toString();
        held.setLength(0);
        return rest;
    }

    /** Where a tool call begins, or -1. Detects the call as soon as its key is
     * named, so a large write_file payload can never leak while the object is
     * still streaming, and confirms by balanced parse for keys in any order. */
    private static int toolCallStart(String text) {
        int start = text.indexOf('{');
        while (start >= 0) {
            int key = start + 1;
            while (key < text.length() && Character.isWhitespace(text.charAt(key))) {
                key++;
            }
            if (text.startsWith("\"tool\"", key)) {
                int colon = key + 6;
                while (colon < text.length() && Character.isWhitespace(text.charAt(colon))) {
                    colon++;
                }
                if (colon < text.length() && text.charAt(colon) == ':') {
                    return start;
                }
            }
            int end = balancedEnd(text, start);
            if (end >= 0) {
                try {
                    if (new JSONObject(text.substring(start, end + 1)).has("tool")) {
                        return start;
                    }
                } catch (JSONException notTool) {
                    // Not an object that names a tool; keep scanning.
                }
            }
            start = text.indexOf('{', start + 1);
        }
        return -1;
    }

    /** The '}' that closes the '{' at start, or -1 while still unbalanced. */
    private static int balancedEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** How much of the text may be released: everything before the trailing
     * sentence stays held, so a lead-in can still be recalled if a tool call
     * follows it. */
    private static int cutAfterBoundary(String text) {
        int len = text.length();
        int lastTerm = -1;
        int prevTerm = -1;
        for (int i = len - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '?' || c == '!' || c == '\n') {
                if (lastTerm < 0) {
                    lastTerm = i;
                } else {
                    prevTerm = i;
                    break;
                }
            }
        }
        int cut = prevTerm < 0 ? 0 : prevTerm + 1;
        if (len - cut > MAX_HOLD) {
            cut = len - MAX_HOLD;
        }
        return cut;
    }
}
