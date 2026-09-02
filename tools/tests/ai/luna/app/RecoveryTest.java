package ai.luna.app;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

/**
 * Failures, read as the model will read them.
 *
 * <p>The assertions are mostly about what a message must <em>not</em> contain:
 * a Java class name, a stack frame, an absolute device path. A model handed
 * {@code java.io.FileNotFoundException} either parrots it at the person or
 * invents a result, so the test insists every failure names a next action
 * instead.
 */
public final class RecoveryTest {

    private static int passed;
    private static int failed;

    private static final List<String> TOOLS = Arrays.asList(
        "list_files", "read_file", "write_file", "edit_file", "create_file",
        "delete_file", "rename_file", "search_code", "open_page", "read_page",
        "search_web", "git_clone", "git_read", "git_edit", "git_write", "ask_user");

    public static void main(String[] args) {
        translation();
        hygiene();
        misspelledTools();
        unknownTools();

        System.out.println();
        if (failed > 0) {
            System.out.println(failed + " FAILED, " + passed + " passed");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static void translation() {
        String missing = Recovery.from("read_file",
            new FileNotFoundException("/storage/emulated/0/Alarms/life.js"));
        check("a missing file says to list first", missing.contains("List the folder"));

        String denied = Recovery.from("write_file", new SecurityException("Permission denied"));
        check("a permission failure sends the person to Files",
            denied.contains("grant the folder again"));
        check("and tells the model not to wander",
            denied.contains("do not try another path"));

        String offline = Recovery.from("open_page", new UnknownHostException("nearspace.com.ng"));
        check("an unresolvable host is explained",
            offline.contains("does not resolve"));

        String slow = Recovery.from("open_page", new SocketTimeoutException("timeout"));
        check("a timeout allows exactly one retry",
            slow.contains("One retry is reasonable"));

        String full = Recovery.from("write_file", new IOException("ENOSPC: no space left"));
        check("a full disk says retrying will not help",
            full.contains("will not help"));

        String huge = Recovery.from("read_file", new OutOfMemoryError("heap"));
        check("running out of memory suggests asking for less",
            huge.contains("smaller part"));

        String odd = Recovery.from("git_clone", new IllegalStateException("something strange"));
        check("an unrecognised failure names the tool", odd.contains("git_clone"));
        check("and warns against a third attempt",
            odd.contains("fails twice"));
    }

    private static void hygiene() {
        Throwable[] all = {
            new FileNotFoundException("/storage/emulated/0/secret/notes.txt"),
            new SecurityException("EACCES"),
            new SocketTimeoutException("timed out"),
            new IllegalStateException("java.lang.IllegalStateException: nested"),
            new IOException("boom"),
        };
        for (Throwable error : all) {
            String message = Recovery.from("read_file", error);
            check("no stack frame leaks for " + error.getClass().getSimpleName(),
                !message.contains("\\tat ") && !message.contains("java.io.")
                    && !message.contains("java.lang."));
            check("no device path leaks for " + error.getClass().getSimpleName(),
                !message.contains("/storage/") && !message.contains("/data/"));
            check("it stays short for " + error.getClass().getSimpleName(),
                message.length() < 260);
            check("it is a sentence for " + error.getClass().getSimpleName(),
                message.endsWith(".") || message.endsWith("?"));
        }

        String silent = Recovery.from("read_file", new IOException());
        check("an exception with no message still advises",
            silent.length() > 40 && !silent.contains("null"));
    }

    private static void misspelledTools() {
        check("a truncated name is matched",
            Recovery.unknownTool("read", TOOLS).contains("read_file"));
        check("an overlong name is matched",
            Recovery.unknownTool("read_file_contents", TOOLS).contains("read_file"));
        check("a hyphen instead of an underscore is matched",
            Recovery.unknownTool("read-file", TOOLS).contains("read_file"));
        check("a typo is matched",
            Recovery.unknownTool("wrtie_file", TOOLS).contains("write_file"));
        check("a plausible alias is matched",
            Recovery.unknownTool("edit", TOOLS).contains("edit_file"));
        check("the suggestion is phrased as a question",
            Recovery.unknownTool("read", TOOLS).contains("Did you mean"));
    }

    private static void unknownTools() {
        // A tool from another agent's vocabulary entirely. There is nothing to
        // suggest, so the run's real tools should be listed instead.
        String invented = Recovery.unknownTool("execute_bash_command", TOOLS);
        check("a wholly invented tool is refused",
            invented.contains("no tool called execute_bash_command"));
        check("and the real tools are offered",
            invented.contains("available in this run"));
        check("the list names a real tool", invented.contains("list_files"));

        check("an empty catalogue does not crash",
            Recovery.unknownTool("anything", Arrays.<String>asList()).length() > 10);
        check("a null catalogue does not crash",
            Recovery.unknownTool("anything", null).length() > 10);
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
