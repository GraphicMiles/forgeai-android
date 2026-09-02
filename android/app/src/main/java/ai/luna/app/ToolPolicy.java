package ai.luna.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Who is allowed to do what, without asking.
 *
 * <p>There is one gate, and it is a single switch. With "ask me first" on,
 * Luna stops before anything that alters your files, anything that leaves the
 * device, and anything that reads material that looks private. With it off she
 * works unsupervised. Nothing is configured tool by tool: a wall of per-tool
 * permissions is a decision you make once, badly, months before the situation
 * it applies to.
 *
 * <p>The model never sees this decision. It is made here, after the call is
 * parsed and before anything happens.
 */
public final class ToolPolicy {

    public static final List<String> READ_ONLY = Arrays.asList(
        "list_files", "read_file", "search_code", "respond",
        "git_status", "git_log", "git_diff", "git_list", "git_read"
    );

    public static final List<String> MUTATING = Arrays.asList(
        "write_file", "edit_file", "create_file", "create_folder", "delete_file", "rename_file",
        "open_page", "read_page", "search_web", "github_file", "ask_user",
        "git_clone", "git_pull", "git_push", "git_commit",
        "git_write", "git_edit", "git_create", "git_delete", "git_move"
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

    /** Tools that need a granted folder to mean anything. */
    public static final List<String> NEEDS_FOLDER = Arrays.asList(
        "list_files", "read_file", "search_code", "write_file", "edit_file", "create_file",
        "create_folder", "delete_file", "rename_file"
    );

    public static boolean needsFolder(String tool) {
        return NEEDS_FOLDER.contains(tool);
    }

    /** Names that mean the contents are somebody's secret. */
    private static final String[] SENSITIVE = {
        ".env", ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", "id_rsa", "id_ed25519",
        ".ssh/", ".netrc", ".git-credentials", "credential", "secret", "password", "passwd",
        "token", "apikey", "api_key", "wallet", "seed-phrase", "seedphrase", "shadow",
    };

    /**
     * True when a path looks like it holds a key, a password or a private
     * account file. Reading one is not destructive, but it is the kind of thing
     * a person wants to be asked about, because the contents then travel
     * wherever the model runs.
     */
    public static boolean isSensitive(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String lower = path.toLowerCase(java.util.Locale.US);
        for (String marker : SENSITIVE) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** What a held tool should do this time. */
    public enum Decision { RUN, ASK }

    public static Decision decide(String tool, String path, Prefs prefs) {
        // The switch is off: you have said to get on with it.
        if (prefs.unattended()) {
            return Decision.RUN;
        }
        // ask_user is the asking. Holding it behind an approval would mean
        // approving a question before you are allowed to be asked it.
        if ("ask_user".equals(tool)) {
            return Decision.RUN;
        }
        if (isMutating(tool)) {
            return Decision.ASK;
        }
        if ("read_file".equals(tool) && isSensitive(path)) {
            return Decision.ASK;
        }
        return Decision.RUN;
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
            case "edit_file":
                return "Change part of " + safePath + "?";
            case "git_edit":
                return "Change part of " + safePath + " in the repository?";
            case "git_write": {
                int lines = content == null || content.isEmpty() ? 0 : content.split("\n", -1).length;
                return "Replace what is in " + safePath + " with "
                    + lines + (lines == 1 ? " line?" : " lines?");
            }
            case "git_create":
                return "Create " + safePath + " in the repository?";
            case "git_delete":
                return "Delete " + safePath + " from the repository?";
            case "git_move":
                return "Move " + safePath + " inside the repository?";
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
            case "search_web":
                return "Search the web for \"" + safePath + "\"?";
            case "github_file":
                return "Fetch " + safePath + " from GitHub?";
            case "git_clone":
                return "Clone " + safePath + " into Luna's git workspace?";
            case "git_pull":
                return "Pull the newest changes into " + safePath + "?";
            case "git_push":
                return "Push " + safePath + " to its remote?";
            case "git_commit":
                return "Commit the changes in " + safePath + "?";
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
            case "search_web":
                return "A search query leaves this device to a search engine.";
            case "github_file":
                return "Your GitHub token is sent to github.com to fetch this file.";
            case "git_clone":
                return "A repository is downloaded from the internet to this device.";
            case "git_pull":
                return "The remote is contacted and new commits are written here.";
            case "git_push":
                return "Your changes and your GitHub token are sent to the remote repository.";
            case "git_commit":
                return "A commit is recorded in Luna's git workspace.";
            case "edit_file":
                return "Only the matched lines change. The rest of the file is untouched, "
                    + "and the old contents are backed up first.";
            case "git_edit":
                return "Only the matched lines change in the cloned repository.";
            case "git_write":
                return "The file in the cloned repository is overwritten. There is no backup, "
                    + "but the change is not pushed until you ask.";
            case "git_create":
                return "A new file appears in the cloned repository.";
            case "git_delete":
                return "It is removed from the cloned repository. There is no backup.";
            case "git_move":
                return "The file changes place inside the cloned repository.";
            default:
                return path == null ? "" : path;
        }
    }
}
