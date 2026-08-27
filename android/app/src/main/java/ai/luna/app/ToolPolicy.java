package ai.luna.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Who is allowed to do what, without asking.
 *
 * Read-only tools always run. Mutating tools stop for approval unless the user
 * has turned on unattended mode. The model never sees this decision — it is
 * made here, after the call is parsed and before anything happens.
 */
public final class ToolPolicy {

    public static final List<String> READ_ONLY = Arrays.asList(
        "list_files", "read_file", "search_code", "ask_user", "respond"
    );

    public static final List<String> MUTATING = Arrays.asList(
        "write_file", "create_file", "create_folder", "delete_file", "rename_file"
    );

    private static final Set<String> READ_ONLY_SET = new HashSet<>(READ_ONLY);

    private ToolPolicy() {
    }

    public static boolean isKnown(String tool) {
        return READ_ONLY_SET.contains(tool) || MUTATING.contains(tool);
    }

    public static boolean isMutating(String tool) {
        return !READ_ONLY_SET.contains(tool);
    }

    /** True when the call has to be shown to the user before it runs. */
    public static boolean needsApproval(String tool, boolean unattended) {
        if (!isMutating(tool)) {
            return false;
        }
        return !unattended;
    }

    /** One plain sentence describing what is about to happen. */
    public static String describe(String tool, String path, String content) {
        String safePath = path == null ? "" : path;
        switch (tool) {
            case "write_file": {
                int lines = content == null || content.isEmpty() ? 0 : content.split("\n", -1).length;
                return "Overwrite " + safePath + " with " + lines + " lines?";
            }
            case "create_file":
                return "Create " + safePath + "?";
            case "create_folder":
                return "Create the folder " + safePath + "?";
            case "delete_file":
                return "Delete " + safePath + "?";
            case "rename_file":
                return "Rename " + safePath + "?";
            default:
                return "Run " + tool + "?";
        }
    }

    /** The consequence line under the headline. Never softened. */
    public static String consequence(String tool, String path) {
        switch (tool) {
            case "delete_file":
                return "A backup is kept, but the file leaves the folder.";
            case "write_file":
                return "The current contents are backed up first.";
            default:
                return path == null ? "" : path;
        }
    }
}
