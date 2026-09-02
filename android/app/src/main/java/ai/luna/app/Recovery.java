package ai.luna.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a failure into an instruction.
 *
 * <p>The model never sees a Java exception. {@code java.io.FileNotFoundException:
 * /storage/emulated/0/x} tells it nothing it can act on, so it does one of three
 * unhelpful things: invents a result, repeats the identical call, or parrots the
 * class name at the person. Every message here instead says what happened and
 * what to do next, because the observation is the only place the model can be
 * steered once a run is under way.
 *
 * <p>Nothing here is a stack trace and nothing leaks an absolute device path:
 * both waste context and neither helps a model choose a better next call.
 */
final class Recovery {

    private Recovery() {
    }

    /**
     * A failed tool call, rewritten as advice.
     *
     * @param tool  the tool that failed, so the advice can name a real next step
     * @param error whatever was thrown
     */
    static String from(String tool, Throwable error) {
        String raw = error.getMessage() == null ? error.toString() : error.getMessage();
        String kind = error.getClass().getSimpleName();
        String lower = (kind + " " + raw).toLowerCase(Locale.ROOT);

        if (lower.contains("filenotfound") || lower.contains("no such file")) {
            return "Failed: there is nothing at that path. List the folder first and use a "
                + "name you saw in the listing, rather than one you expect to be there.";
        }
        if (lower.contains("permission") || lower.contains("eacces")
            || lower.contains("securityexception")) {
            return "Failed: the app is not allowed to touch that. The person has to grant "
                + "the folder again in Files. Tell them that; do not try another path.";
        }
        if (lower.contains("enospc") || lower.contains("no space")) {
            return "Failed: the device is out of storage. Tell the person; retrying will "
                + "not help.";
        }
        if (lower.contains("unknownhost") || lower.contains("unable to resolve")) {
            return "Failed: that address does not resolve. Check the spelling of the "
                + "domain, or tell the person the site could not be reached.";
        }
        if (lower.contains("timeout") || lower.contains("timed out")
            || lower.contains("sockettimeout")) {
            return "Failed: it took too long to answer. One retry is reasonable. If it "
                + "times out again, say so rather than trying a third time.";
        }
        if (lower.contains("sslhandshake") || lower.contains("certpath")
            || lower.contains("certificate")) {
            return "Failed: the site's security certificate could not be trusted. Do not "
                + "retry; tell the person the connection was not safe.";
        }
        if (lower.contains("connect") && (lower.contains("refused") || lower.contains("econn"))) {
            return "Failed: nothing answered at that address. Tell the person the site "
                + "appears to be down.";
        }
        if (lower.contains("network") || lower.contains("offline")
            || lower.contains("unreachable")) {
            return "Failed: the device seems to be offline. Tell the person to check their "
                + "connection; retrying now will not help.";
        }
        if (lower.contains("outofmemory")) {
            return "Failed: that was too big to hold in memory. Ask for a smaller part of "
                + "it -- a single file rather than a whole folder.";
        }
        if (lower.contains("jsonexception") || lower.contains("json")) {
            return "Failed: the arguments were not valid JSON. Send the call again with "
                + "properly quoted values.";
        }
        if (lower.contains("illegalargument") || lower.contains("numberformat")) {
            return "Failed: one of the arguments was not of the expected kind -- " + brief(raw)
                + ". Check the tool's inputs and send it again.";
        }
        if (lower.contains("interrupted")) {
            return "Failed: the run was stopped. Do not retry.";
        }

        // Anything unrecognised. Say plainly that it is unrecognised rather than
        // dressing it up, so the model does not read a specific cause into it.
        return "Failed: " + tool + " could not finish -- " + brief(raw)
            + ". If the same call fails twice, try a different approach or tell the "
            + "person what went wrong instead of repeating it.";
    }

    /**
     * A call to a tool that does not exist.
     *
     * <p>Usually a near miss rather than an invention: a model reaches for
     * {@code read} or {@code edit_file} when the run only has {@code git_edit}.
     * Naming the closest real tools converts a dead end into a next call.
     */
    static String unknownTool(String asked, List<String> available) {
        List<String> close = nearest(asked, available);
        StringBuilder out = new StringBuilder();
        out.append("There is no tool called ").append(asked).append(".");
        if (!close.isEmpty()) {
            out.append(" Did you mean ");
            for (int index = 0; index < close.size(); index++) {
                if (index > 0) {
                    out.append(index == close.size() - 1 ? " or " : ", ");
                }
                out.append(close.get(index));
            }
            out.append("?");
        } else if (available != null && !available.isEmpty()) {
            out.append(" The tools available in this run are: ")
                .append(String.join(", ", available)).append(".");
        }
        return out.toString();
    }

    /** The handful of real tool names closest to what was asked for. */
    private static List<String> nearest(String asked, List<String> available) {
        List<String> found = new ArrayList<>();
        if (asked == null || available == null) {
            return found;
        }
        String want = asked.toLowerCase(Locale.ROOT).replace("-", "_");

        // Substring either way catches the common misses: "read" for "read_file",
        // "read_file_contents" for "read_file".
        for (String real : available) {
            String candidate = real.toLowerCase(Locale.ROOT);
            if (candidate.contains(want) || want.contains(candidate)) {
                found.add(real);
            }
        }
        if (!found.isEmpty()) {
            return found.subList(0, Math.min(3, found.size()));
        }

        // Otherwise fall back to edit distance, for a genuine typo.
        int best = Integer.MAX_VALUE;
        for (String real : available) {
            int distance = distance(want, real.toLowerCase(Locale.ROOT));
            if (distance < best) {
                best = distance;
                found.clear();
                found.add(real);
            } else if (distance == best && found.size() < 3) {
                found.add(real);
            }
        }
        // Three edits is about where "a typo" stops and "a different word" starts.
        return best <= 3 ? found : new ArrayList<String>();
    }

    /** Levenshtein, two rows at a time. */
    private static int distance(String from, String to) {
        int[] previous = new int[to.length() + 1];
        int[] current = new int[to.length() + 1];
        for (int index = 0; index <= to.length(); index++) {
            previous[index] = index;
        }
        for (int row = 1; row <= from.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= to.length(); column++) {
                int cost = from.charAt(row - 1) == to.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(
                    Math.min(current[column - 1] + 1, previous[column] + 1),
                    previous[column - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[to.length()];
    }

    /**
     * The gist of a raw message, with device paths and noise taken out.
     *
     * <p>An absolute path in app storage means nothing to the model and nothing
     * to the person reading the reply over its shoulder.
     */
    private static String brief(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "no reason given";
        }
        String text = raw.replace('\n', ' ').trim();
        text = text.replaceAll("/(?:storage|data|sdcard)/\\S+", "the file");
        text = text.replaceAll("\\b[a-z]+(?:\\.[a-z]+)+\\.([A-Z]\\w+Exception)\\b", "$1");
        if (text.length() > 160) {
            text = text.substring(0, 157).trim() + "...";
        }
        return text;
    }
}
