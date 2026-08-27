package ai.luna.app;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The tools themselves. Each one returns a short, factual string that goes
 * straight back into the model's context — no exceptions escape, because a
 * failed tool is information, not a crash.
 */
public final class Tools {

    private static final int MAX_OBSERVATION = 2000;

    private Tools() {
    }

    public static String run(WorkspaceStore workspace, String tool, JSONObject args) {
        try {
            switch (tool) {
                case "list_files":
                    return listFiles(workspace, args.optString("path", ""));
                case "read_file":
                    return readFile(workspace, args.optString("path", ""));
                case "search_code":
                    return search(workspace, args.optString("query", ""));
                case "write_file":
                    workspace.writeText(args.optString("path", ""), args.optString("content", ""));
                    return "Wrote " + args.optString("path", "") + ".";
                case "create_file":
                    workspace.createFile(args.optString("path", ""));
                    return "Created " + args.optString("path", "") + ".";
                case "create_folder":
                    workspace.createFolder(args.optString("path", ""));
                    return "Created the folder " + args.optString("path", "") + ".";
                case "delete_file":
                    workspace.delete(args.optString("path", ""));
                    return "Deleted " + args.optString("path", "") + ". A backup was kept.";
                case "rename_file":
                    workspace.rename(args.optString("path", ""), args.optString("newName", ""));
                    return "Renamed to " + args.optString("newName", "") + ".";
                case "ask_user":
                    return "Ask the user directly in your next plain-text reply.";
                default:
                    return "Unknown tool: " + tool;
            }
        } catch (Exception error) {
            String message = error.getMessage();
            return "Failed: " + (message == null ? error.toString() : message);
        }
    }

    private static String listFiles(WorkspaceStore workspace, String path) throws Exception {
        JSONArray entries = workspace.list(path);
        if (entries.length() == 0) {
            return "Empty (or no folder has been granted).";
        }
        StringBuilder out = new StringBuilder();
        out.append(entries.length()).append(" items in ").append(path.isEmpty() ? "the root" : path).append(":\n");
        for (int index = 0; index < entries.length() && index < 80; index++) {
            JSONObject entry = entries.getJSONObject(index);
            out.append(entry.getString("type").equals("folder") ? "dir  " : "file ");
            out.append(entry.getString("name"));
            if (entry.getBoolean("locked")) {
                out.append("  [protected]");
            } else if (!entry.getString("type").equals("folder")) {
                out.append("  ").append(entry.getLong("size")).append(" B");
            }
            out.append('\n');
        }
        return clamp(out.toString());
    }

    private static String readFile(WorkspaceStore workspace, String path) throws Exception {
        String text = workspace.readText(path);
        return clamp(path + ":\n" + text);
    }

    private static String search(WorkspaceStore workspace, String query) throws Exception {
        JSONArray hits = workspace.search(query, 12);
        if (hits.length() == 0) {
            return "No matches for " + query + ".";
        }
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < hits.length(); index++) {
            JSONObject hit = hits.getJSONObject(index);
            out.append(hit.getString("path")).append(':').append(hit.getInt("line")).append("  ")
                .append(hit.getString("excerpt")).append('\n');
        }
        return clamp(out.toString());
    }

    private static String clamp(String value) {
        if (value.length() <= MAX_OBSERVATION) {
            return value;
        }
        return value.substring(0, MAX_OBSERVATION) + "\n… truncated, read a smaller range if you need more.";
    }
}
