package ai.luna.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Who is allowed to do what, without asking.
 *
 * Reading is free. Anything that changes a file, or reaches off the device, is
 * held back. Held tools obey a rule you set per tool — ask, always, or never —
 * and the global "ask me first" switch decides what an unset rule means. The
 * model never sees this decision: it is made here, after the call is parsed and
 * before anything happens.
 */
public final class ToolPolicy {

    public static final List<String> READ_ONLY = Arrays.asList(
        "list_files", "read_file", "search_code", "respond"
    );

    public static final List<String> MUTATING = Arrays.asList(
        "write_file", "create_file", "create_folder", "delete_file", "rename_file",
        "open_page", "read_page", "github_file", "ask_user"
    );

    private static final Set<String> READ_ONLY_SET = new HashSet<>(READ_ONLY);
    private static final Set<String> MUTATING_SET = new HashSet<>(MUTATING);

    private ToolPolicy() {
    }

    public static boolean isKnown(String tool) {
        return READ_ONLY_SET.contains(tool) || MUTATING_SET.contains(tool);
    }

    public static boolean isMutating(String tool) {
        return !READ_ONLY_SET.contains(tool);
    }

    /** What a held tool should do this time. */
    public enum Decision { RUN, ASK, REFUSE }

    public static Decision decide(String tool, Prefs prefs) {
        if (!isMutating(tool)) {
            return Decision.RUN;
        }
        String rule = prefs.toolRule(tool);
        if (Prefs.RULE_NEVER.equals(rule)) {
            return Decision.REFUSE;
        }
        if (Prefs.RULE_ALWAYS.equals(rule)) {
            return Decision.RUN;
        }
        // ask_user is the one held tool that has to reach the person by design.
        if ("ask_user".equals(tool)) {
            return Decision.RUN;
        }
        return prefs.unattended() ? Decision.RUN : Decision.ASK;
    }

    /** One plain sentence describing what is about to happen. */
    public static String describe(String tool, String path, String content) {
        String safePath = path == null ? "" : path;
        switch (tool) {
            case "write_file": {
                int lines = content == null || content.isEmpty() ? 0 : content.split("\n", -1).length;
                // One line is one line. A count that cannot count is the first
                // thing a person notices, and the last thing they trust.
                return "Replace what is in " + safePath + " with "
                    + lines + (lines == 1 ? " line?" : " lines?");
            }
            case "create_file":
                return "Create " + safePath + "?";
            case "create_folder":
                return "Create the folder " + safePath + "?";
            case "delete_file":
                return "Delete " + safePath + "?";
            case "rename_file":
                return "Rename " + safePath + "?";
            case "open_page":
                return "Open " + safePath + " in the background browser?";
            case "read_page":
                return "Read the page that is open?";
            case "github_file":
                return "Fetch " + safePath + " from GitHub?";
            default:
                return "Let Luna " + plainName(tool) + "?";
        }
    }

    /** A tool's name as a person would say it, for the rare unnamed case. */
    private static String plainName(String tool) {
        return tool == null ? "do that" : tool.replace('_', ' ');
    }

    /** The consequence line under the headline. Never softened. */
    public static String consequence(String tool, String path) {
        switch (tool) {
            case "delete_file":
                return "A backup is kept, but the file leaves the folder.";
            case "write_file":
                return "The current contents are backed up first.";
            case "open_page":
                return "A page load off this device. Cookies are thrown away when the job ends.";
            case "read_page":
                return "The words on the page become part of what the model reads.";
            case "github_file":
                return "Your GitHub token is sent to github.com to fetch this file.";
            default:
                return path == null ? "" : path;
        }
    }
}
