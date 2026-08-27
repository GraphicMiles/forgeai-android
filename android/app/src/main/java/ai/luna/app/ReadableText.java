package ai.luna.app;

/**
 * Page text, tidied.
 *
 * A model pays for every character, and a web page is mostly furniture. Blank
 * lines collapse, runs of spaces collapse, and the result is cut at a length
 * the context can actually hold — with a line saying so, because a silent
 * truncation reads like the page ended there.
 */
public final class ReadableText {

    private ReadableText() {
    }

    /** \\u0041 and friends. Pages are full of them once innerText is JSON-encoded. */
    static String unescapeUnicode(String value) {
        int at = value.indexOf("\\u");
        if (at < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            if (index + 5 < value.length() && value.charAt(index) == '\\' && value.charAt(index + 1) == 'u') {
                try {
                    out.append((char) Integer.parseInt(value.substring(index + 2, index + 6), 16));
                    index += 6;
                    continue;
                } catch (NumberFormatException notAnEscape) {
                    // Fall through and copy the characters as they are.
                }
            }
            out.append(value.charAt(index));
            index++;
        }
        return out.toString();
    }

    public static String clean(String raw, int limit) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw;
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() > 1) {
            // evaluateJavascript hands back a JSON string.
            text = text.substring(1, text.length() - 1)
                .replace("\\n", "\n").replace("\\t", " ").replace("\\\"", "\"")
                .replace("\\\\", "\\");
            text = unescapeUnicode(text);
        }
        StringBuilder out = new StringBuilder();
        int blankRun = 0;
        for (String line : text.split("\n")) {
            String tidy = line.replaceAll("[ \\t\\u00a0]+", " ").trim();
            if (tidy.isEmpty()) {
                blankRun++;
                if (blankRun > 1) {
                    continue;
                }
            } else {
                blankRun = 0;
            }
            out.append(tidy).append('\n');
            if (out.length() > limit) {
                out.append("\n… the rest of the page was not read.");
                break;
            }
        }
        return out.toString().trim();
    }
}
