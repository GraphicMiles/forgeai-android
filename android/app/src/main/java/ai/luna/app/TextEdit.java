package ai.luna.app;

import java.util.ArrayList;
import java.util.List;

/**
 * Find a block of text in a file and put a different one in its place.
 *
 * <p>Rewriting a whole file to change three lines is how a model deletes work
 * it never looked at: it re-emits everything it remembers, and whatever it did
 * not remember is gone. A targeted edit cannot do that, because the rest of the
 * file is never in play.
 *
 * <p>The difficulty is that models do not reproduce a search block exactly.
 * They normalise indentation, convert tabs, drop a trailing space, or re-wrap.
 * An exact-match-only implementation therefore fails constantly, and the
 * documented consequence in every agent that has tried it is the model giving
 * up and overwriting the file — the very outcome this is meant to prevent. So
 * matching falls back through progressively looser strategies, in the order
 * Aider settled on after the same discovery:
 *
 * <ol>
 *   <li>exact — the block appears character for character;
 *   <li>line-trimmed — every line matches once trailing space is ignored;
 *   <li>whitespace-flexible — runs of blank space are treated as equivalent,
 *       so tabs and spaces stop mattering;
 *   <li>anchored — the first and last lines match and the block is unique,
 *       which catches a middle the model paraphrased.
 * </ol>
 *
 * <p>Every strategy after the first insists the match be <em>unique</em>. A
 * loose rule that hits twice is worse than no rule at all: it edits the wrong
 * half of the file and reports success.
 */
public final class TextEdit {

    /** What happened, and the text to keep if it worked. */
    public static final class Result {
        public final boolean ok;
        public final String text;
        public final String how;
        public final String problem;

        private Result(boolean ok, String text, String how, String problem) {
            this.ok = ok;
            this.text = text;
            this.how = how;
            this.problem = problem;
        }

        static Result worked(String text, String how) {
            return new Result(true, text, how, null);
        }

        static Result failed(String problem) {
            return new Result(false, null, null, problem);
        }
    }

    private TextEdit() {
    }

    /**
     * Replace {@code find} with {@code replace} in {@code source}.
     *
     * @param all when true every occurrence is replaced, and an ambiguous
     *            match stops being an error
     */
    public static Result apply(String source, String find, String replace, boolean all) {
        if (source == null) {
            return Result.failed("There is no file content to edit.");
        }
        if (find == null || find.isEmpty()) {
            return Result.failed("The text to find is empty. Give the exact lines to replace.");
        }
        String wanted = replace == null ? "" : replace;

        if (find.equals(wanted)) {
            return Result.failed("The text to find and the replacement are identical, "
                + "so this edit would change nothing.");
        }

        // 1. Exact. The overwhelmingly common case when it works at all.
        int first = source.indexOf(find);
        if (first >= 0) {
            // Count the way the replacement will actually consume the text --
            // non-overlapping -- so that the check and the count agree. Looking
            // one character ahead instead finds an overlap of the block with
            // itself ("aa" inside "aaa"), which is a single replaceable
            // occurrence, and refusing it produced the nonsense "appears 1
            // times".
            int total = count(source, find);
            if (total > 1 && !all) {
                return Result.failed("That text appears " + total
                    + " times, so it is not clear which one to change. Include a few more "
                    + "surrounding lines to make it unique, or set all to true to change "
                    + "every one.");
            }
            String out = all ? replaceAll(source, find, wanted)
                : source.substring(0, first) + wanted + source.substring(first + find.length());
            return Result.worked(out, "exact");
        }

        // Everything below works line-wise, because every difference that
        // matters here is a difference in how lines were spaced.
        List<String> haystack = lines(source);
        List<String> needle = lines(find);
        if (needle.isEmpty()) {
            return Result.failed("The text to find is empty.");
        }

        Span span = findTrimmed(haystack, needle);
        String how = "line-trimmed";
        if (span == null) {
            span = findFlexible(haystack, needle);
            how = "whitespace-flexible";
        }
        if (span == null) {
            span = findAnchored(haystack, needle);
            how = "anchored";
        }
        if (span == null) {
            return Result.failed("That text is not in the file. Read it again and copy the "
                + "lines exactly as they appear, including their indentation.");
        }
        if (span.ambiguous && !all) {
            return Result.failed("More than one part of the file could be the text you meant. "
                + "Include a few more surrounding lines so there is only one match.");
        }

        // The replacement is re-indented to sit where the old block sat. A
        // model that dropped the leading spaces should not thereby dedent
        // somebody's method.
        String body = reindent(wanted, needle, haystack.subList(span.from, span.to));

        StringBuilder out = new StringBuilder();
        for (int index = 0; index < span.from; index++) {
            out.append(haystack.get(index));
        }
        out.append(body);
        if (!body.endsWith("\n") && span.to < haystack.size()) {
            out.append('\n');
        }
        for (int index = span.to; index < haystack.size(); index++) {
            out.append(haystack.get(index));
        }
        return Result.worked(out.toString(), how);
    }

    // --- strategies -----------------------------------------------------------

    /** Where a block was found, and whether something else looked like it too. */
    private static final class Span {
        final int from;
        final int to;
        final boolean ambiguous;

        Span(int from, int to, boolean ambiguous) {
            this.from = from;
            this.to = to;
            this.ambiguous = ambiguous;
        }
    }

    /** Every line equal once trailing whitespace is ignored. */
    private static Span findTrimmed(List<String> haystack, List<String> needle) {
        return scan(haystack, needle, false);
    }

    /** Every line equal once all runs of whitespace are flattened. */
    private static Span findFlexible(List<String> haystack, List<String> needle) {
        return scan(haystack, needle, true);
    }

    private static Span scan(List<String> haystack, List<String> needle, boolean flexible) {
        int span = needle.size();
        int found = -1;
        boolean twice = false;
        for (int start = 0; start + span <= haystack.size(); start++) {
            boolean same = true;
            for (int offset = 0; offset < span; offset++) {
                String left = haystack.get(start + offset);
                String right = needle.get(offset);
                if (!(flexible ? flatten(left).equals(flatten(right))
                    : left.trim().equals(right.trim()))) {
                    same = false;
                    break;
                }
            }
            if (same) {
                if (found < 0) {
                    found = start;
                } else {
                    twice = true;
                    break;
                }
            }
        }
        return found < 0 ? null : new Span(found, found + span, twice);
    }

    /**
     * First and last line match; the middle is taken on trust.
     *
     * <p>Only for blocks of three lines or more — with fewer there is no middle
     * and this would just be a sloppier version of the strategies above.
     */
    private static Span findAnchored(List<String> haystack, List<String> needle) {
        if (needle.size() < 3) {
            return null;
        }
        String top = needle.get(0).trim();
        String bottom = needle.get(needle.size() - 1).trim();
        if (top.isEmpty() || bottom.isEmpty()) {
            return null;
        }
        int span = needle.size();
        int found = -1;
        boolean twice = false;
        for (int start = 0; start + span <= haystack.size(); start++) {
            if (!haystack.get(start).trim().equals(top)) {
                continue;
            }
            if (!haystack.get(start + span - 1).trim().equals(bottom)) {
                continue;
            }
            if (found < 0) {
                found = start;
            } else {
                twice = true;
                break;
            }
        }
        return found < 0 ? null : new Span(found, found + span, twice);
    }

    // --- text helpers ---------------------------------------------------------

    /** Split keeping the line endings, so a rebuild is byte-faithful. */
    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        int at = 0;
        while (at < text.length()) {
            int newline = text.indexOf('\n', at);
            if (newline < 0) {
                out.add(text.substring(at));
                break;
            }
            out.add(text.substring(at, newline + 1));
            at = newline + 1;
        }
        return out;
    }

    private static String flatten(String line) {
        return line.replaceAll("\\s+", " ").trim();
    }

    private static String leading(String line) {
        int at = 0;
        while (at < line.length() && (line.charAt(at) == ' ' || line.charAt(at) == '\t')) {
            at++;
        }
        return line.substring(0, at);
    }

    /**
     * Move a replacement to the indentation the block it replaces actually had.
     *
     * <p>A model routinely sends a block flattened to the left margin. Writing
     * that back verbatim would dedent somebody's method body, so the
     * indentation has to be restored — and the file, not the model, is the
     * authority on what it should be.
     *
     * <p>Two cases. When the replacement has the same number of lines as the
     * block it replaces, each line inherits the indentation of the file line it
     * stands in for; that reproduces the original shape exactly, nesting and
     * all. Otherwise the structure is genuinely new and there is nothing to
     * copy line-for-line, so every line is shifted by the difference at the top
     * of the block and the replacement's own relative indentation is kept.
     */
    private static String reindent(String replacement, List<String> needle,
                                   List<String> matched) {
        List<String> body = lines(replacement);
        if (body.isEmpty()) {
            return replacement;
        }

        if (body.size() == matched.size()) {
            StringBuilder out = new StringBuilder();
            for (int index = 0; index < body.size(); index++) {
                out.append(withIndent(body.get(index), leading(matched.get(index))));
            }
            return out.toString();
        }

        String actual = leading(matched.get(0));
        String claimed = leading(needle.get(0));
        if (actual.equals(claimed)) {
            return replacement;
        }
        StringBuilder out = new StringBuilder();
        for (String line : body) {
            String bare = strip(line);
            String ending = line.length() > bare.length() ? line.substring(bare.length()) : "";
            if (bare.trim().isEmpty()) {
                out.append(line);
                continue;
            }
            if (!claimed.isEmpty() && bare.startsWith(claimed)) {
                out.append(actual).append(bare.substring(claimed.length())).append(ending);
            } else if (claimed.isEmpty()) {
                out.append(actual).append(bare).append(ending);
            } else {
                out.append(line);
            }
        }
        return out.toString();
    }

    /** One line, re-indented, keeping whatever line ending it arrived with. */
    private static String withIndent(String line, String indent) {
        String bare = strip(line);
        String ending = line.length() > bare.length() ? line.substring(bare.length()) : "";
        if (bare.trim().isEmpty()) {
            return line;
        }
        return indent + bare.substring(leading(bare).length()) + ending;
    }

    /** The line without its trailing newline, if it has one. */
    private static String strip(String line) {
        if (line.endsWith("\r\n")) {
            return line.substring(0, line.length() - 2);
        }
        if (line.endsWith("\n")) {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }

    private static int count(String source, String find) {
        int total = 0;
        int at = source.indexOf(find);
        while (at >= 0) {
            total++;
            at = source.indexOf(find, at + find.length());
        }
        return total;
    }

    private static String replaceAll(String source, String find, String replacement) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (true) {
            int hit = source.indexOf(find, at);
            if (hit < 0) {
                out.append(source, at, source.length());
                return out.toString();
            }
            out.append(source, at, hit).append(replacement);
            at = hit + find.length();
        }
    }
}
