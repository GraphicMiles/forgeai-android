package ai.luna.app;

/**
 * Targeted editing, executed for real on a plain JVM.
 *
 * <p>The reason this file exists: an edit tool that silently matches the wrong
 * block is worse than one that fails, because it corrupts a file and reports
 * success. So the interesting cases here are not the ones that work — they are
 * the ones that must be <em>refused</em>: ambiguous matches, absent text, and
 * edits that would change nothing.
 */
public final class TextEditTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        exact();
        whitespace();
        ambiguity();
        indentation();
        refusals();
        everyOccurrence();
        preservation();
        overlapping();

        System.out.println();
        if (failed > 0) {
            System.out.println(failed + " FAILED, " + passed + " passed");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    // --- the happy path -------------------------------------------------------

    private static void exact() {
        String file = "function a() {\n  return 1;\n}\n";
        TextEdit.Result result = TextEdit.apply(file, "  return 1;", "  return 2;", false);
        check("an exact block is replaced", result.ok
            && result.text.equals("function a() {\n  return 2;\n}\n"));
        check("and it says how it matched", result.ok && result.how.equals("exact"));

        TextEdit.Result many = TextEdit.apply(
            "a\nb\nc\nd\n", "b\nc\n", "B\nC\n", false);
        check("a multi-line block is replaced", many.ok && many.text.equals("a\nB\nC\nd\n"));
    }

    // --- the cases that make or break the tool --------------------------------

    private static void whitespace() {
        // The model re-indented with spaces; the file uses a tab.
        String tabbed = "class X {\n\tvoid go() {\n\t\trun();\n\t}\n}\n";
        TextEdit.Result spaces = TextEdit.apply(tabbed,
            "    void go() {\n        run();\n    }", "    void go() {\n        walk();\n    }",
            false);
        check("a tab/space mismatch still matches", spaces.ok);
        check("and the file keeps working text", spaces.ok && spaces.text.contains("walk()"));

        // The model dropped a trailing space that is in the file.
        TextEdit.Result trailing = TextEdit.apply(
            "one   \ntwo\n", "one\ntwo", "1\n2", false);
        check("a dropped trailing space still matches", trailing.ok);

        // The model collapsed a run of internal spaces.
        TextEdit.Result runs = TextEdit.apply(
            "int    x =    1;\n", "int x = 1;", "int x = 2;", false);
        check("collapsed internal spacing still matches", runs.ok
            && runs.text.contains("x = 2;"));
    }

    private static void ambiguity() {
        String twice = "log();\nwork();\nlog();\n";
        TextEdit.Result result = TextEdit.apply(twice, "log();", "trace();", false);
        check("an ambiguous exact match is refused", !result.ok);
        check("and the refusal says why", !result.ok && result.problem.contains("appears"));

        // The same, one strategy down: two blocks that differ only in spacing.
        String spaced = "if (a) {\n  go();\n}\nif (b) {\n   go();\n}\n";
        TextEdit.Result loose = TextEdit.apply(spaced, "  go();\n", "  stop();\n", false);
        check("an ambiguous loose match is refused too", !loose.ok);
    }

    private static void indentation() {
        // Found by anchoring at a different indent: the replacement should be
        // moved to where the original block actually sat.
        String nested = "class A {\n    void m() {\n        old();\n    }\n}\n";
        TextEdit.Result result = TextEdit.apply(nested,
            "void m() {\nold();\n}", "void m() {\nfresh();\n}", false);
        check("a block found at another indent is replaced", result.ok);
        if (result.ok) {
            check("and the replacement keeps the file's indentation",
                result.text.contains("    void m() {") && result.text.contains("        fresh();"));
        } else {
            check("and the replacement keeps the file's indentation", false);
        }
    }

    private static void refusals() {
        TextEdit.Result missing = TextEdit.apply("a\nb\n", "zzz", "yyy", false);
        check("text that is not there is refused", !missing.ok);
        check("and the model is told to read again",
            !missing.ok && missing.problem.contains("Read it again"));

        TextEdit.Result empty = TextEdit.apply("a\n", "", "b", false);
        check("an empty search is refused", !empty.ok);

        TextEdit.Result same = TextEdit.apply("a\n", "a", "a", false);
        check("an edit that changes nothing is refused", !same.ok);

        TextEdit.Result noFile = TextEdit.apply(null, "a", "b", false);
        check("a missing file is refused", !noFile.ok);
    }

    private static void everyOccurrence() {
        String twice = "x = 1;\ny = 1;\n";
        TextEdit.Result all = TextEdit.apply(twice, "1;", "2;", true);
        check("all=true changes every occurrence", all.ok
            && all.text.equals("x = 2;\ny = 2;\n"));
        check("and ambiguity stops being an error", all.ok);
    }

    private static void preservation() {
        // Nothing outside the matched block may move, including the last line
        // having no newline of its own.
        String file = "header\n\n  body\n\nfooter";
        TextEdit.Result result = TextEdit.apply(file, "  body", "  BODY", false);
        check("a file with no trailing newline keeps its shape", result.ok
            && result.text.equals("header\n\n  BODY\n\nfooter"));

        String crlf = "a\r\nb\r\nc\r\n";
        TextEdit.Result windows = TextEdit.apply(crlf, "b", "B", false);
        check("windows line endings survive", windows.ok && windows.text.contains("\r\n"));

        // The block appears at the very start and very end.
        TextEdit.Result first = TextEdit.apply("top\nrest\n", "top", "TOP", false);
        check("a match at the first line works", first.ok
            && first.text.equals("TOP\nrest\n"));
        TextEdit.Result last = TextEdit.apply("rest\nend\n", "end", "END", false);
        check("a match at the last line works", last.ok
            && last.text.equals("rest\nEND\n"));
    }

    /**
     * A block that overlaps itself is still one replaceable occurrence.
     *
     * <p>Ambiguity used to be detected by looking one character past the first
     * hit, while the count that went into the message stepped over a whole
     * block. For "aa" inside "aaa" those disagree: the edit was refused as
     * ambiguous and the model was told the text "appears 1 times" -- a refusal
     * it cannot act on, for an edit that was never ambiguous. Both now count
     * the way the replacement actually consumes the text.
     */
    private static void overlapping() {
        TextEdit.Result once = TextEdit.apply("aaa\n", "aa", "b", false);
        check("a self-overlapping block is replaced", once.ok);
        check("and only the first is taken", once.ok && once.text.equals("ba\n"));

        // Genuinely two, once the overlap is resolved the way a replace runs.
        TextEdit.Result twice = TextEdit.apply("aaaa\n", "aa", "b", false);
        check("two non-overlapping copies are ambiguous", !twice.ok);
        check("and the count is the honest one",
            !twice.ok && twice.problem.contains("appears 2 times"));
        check("never a count of one",
            !twice.problem.contains("appears 1 times"));

        TextEdit.Result all = TextEdit.apply("aaaa\n", "aa", "b", true);
        check("all=true takes both", all.ok && all.text.equals("bb\n"));

        // The count in the message has to match what all=true would really do,
        // or the model is told to expect a different edit than it will get.
        TextEdit.Result pair = TextEdit.apply("abab\n", "ab", "X", false);
        check("a clean pair reports two", !pair.ok && pair.problem.contains("appears 2 times"));
        TextEdit.Result both = TextEdit.apply("abab\n", "ab", "X", true);
        check("and all=true changes exactly that many",
            both.ok && both.text.equals("XX\n"));
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
