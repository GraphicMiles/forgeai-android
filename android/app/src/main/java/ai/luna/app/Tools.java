package ai.luna.app;

import ai.luna.contracts.BrowserProvider;
import ai.luna.contracts.GitProvider;
import ai.luna.contracts.SecretProvider;
import ai.luna.contracts.StorageProvider;
import ai.luna.contracts.Trace;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The tools themselves. Each one returns a short, factual string that goes
 * straight back into the model's context — no exceptions escape, because a
 * failed tool is information, not a crash.
 */
public final class Tools {

    private static final int MAX_OBSERVATION = 4000;

    /** Everything a tool is allowed to touch, handed in rather than looked up. */
    public static final class Env {
        // Contracts, not concrete classes: the same tool has to work against a
        // granted Android folder, a git checkout or a box on the network.
        public final StorageProvider workspace;
        public final BrowserProvider browser;
        public final SecretProvider vault;
        public final GitProvider git;
        public final Trace errors;

        public Env(StorageProvider workspace, BrowserProvider browser, SecretProvider vault,
                   GitProvider git, Trace errors) {
            this.workspace = workspace;
            this.browser = browser;
            this.vault = vault;
            this.git = git;
            this.errors = errors;
        }
    }

    private Tools() {
    }

    public static String run(Env env, String tool, JSONObject args) {
        try {
            switch (tool) {
                case "list_files":
                    return listFiles(env.workspace, args.optString("path", ""));
                case "read_file":
                    return readFile(env.workspace, args.optString("path", ""));
                case "search_code":
                    return search(env.workspace, args.optString("query", ""));
                case "write_file":
                    env.workspace.writeText(args.optString("path", ""), args.optString("content", ""));
                    return "Wrote " + args.optString("path", "") + ".";
                case "create_file":
                    env.workspace.createFile(args.optString("path", ""));
                    return "Created " + args.optString("path", "") + ".";
                case "create_folder":
                    env.workspace.createFolder(args.optString("path", ""));
                    return "Created the folder " + args.optString("path", "") + ".";
                case "delete_file":
                    env.workspace.delete(args.optString("path", ""));
                    return "Deleted " + args.optString("path", "") + ". A backup was kept.";
                case "rename_file":
                    env.workspace.rename(args.optString("path", ""), args.optString("newName", ""));
                    return "Renamed to " + args.optString("newName", "") + ".";
                case "open_page":
                    return openPage(env, args.optString("url", args.optString("path", "")));
                case "read_page":
                    return readPage(env);
                case "search_web":
                    return searchWeb(env, args.optString("query", ""));
                case "github_file":
                    return githubFile(env, args);
                case "git_clone":
                    return gitClone(env, args.optString("url", ""), args.optString("name", ""));
                case "git_pull":
                    return gitPull(env, args.optString("path", ""));
                case "git_push":
                    return gitPush(env, args.optString("path", ""));
                case "git_status":
                    return gitStatus(env, args.optString("path", ""));
                case "git_commit":
                    return gitCommit(env, args.optString("path", ""), args.optString("message", ""));
                case "git_log":
                    return gitLog(env, args.optString("path", ""));
                case "git_diff":
                    return gitDiff(env, args.optString("path", ""));
                default:
                    return "Unknown tool: " + tool;
            }
        } catch (Exception error) {
            String message = error.getMessage();
            if (env.errors != null) {
                env.errors.fail(tool, message == null ? String.valueOf(error) : message);
            }
            return "Failed: " + (message == null ? error.toString() : message);
        }
    }

    // --- the web ---------------------------------------------------------------

    private static String openPage(Env env, String url) {
        if (env.browser == null) {
            return "There is no browser available on this device.";
        }
        String refusal = env.browser.open(url, 20000L);
        if (!refusal.isEmpty()) {
            return "Could not open it: " + refusal.replace("refused: ", "") + ".";
        }
        String title = env.browser.currentTitle();
        return "Opened " + env.browser.currentUrl() + (title.isEmpty() ? "" : " — " + title)
            + ". Call read_page to read it.";
    }

    private static String readPage(Env env) {
        if (env.browser == null) {
            return "There is no browser available on this device.";
        }
        String text = env.browser.text();
        if (text.isEmpty()) {
            return "Nothing is open, or the page had no readable text.";
        }
        return clamp("Text of " + env.browser.currentUrl() + ":\n" + text);
    }

    /**
     * A search, run through whichever browser the environment offers.
     *
     * <p>The model never builds a search address itself: the query is encoded
     * into one known, real search page, so the "never invent an address" rule
     * still holds while "check online" questions get answered. The answer is
     * the browser's structured results — one "title || address || snippet"
     * line per result — never the raw page.
     */
    private static String searchWeb(Env env, String query) throws Exception {
        if (env.browser == null) {
            return "There is no browser available on this device.";
        }
        if (query == null || query.trim().isEmpty()) {
            return "Give me a query to search for.";
        }
        String url = "https://www.google.com/search?q="
            + URLEncoder.encode(query.trim(), "UTF-8") + "&hl=en&num=10";
        String refusal = env.browser.open(url, 20000L);
        if (!refusal.isEmpty()) {
            return "The search could not run: " + refusal.replace("refused: ", "") + ".";
        }
        String results = env.browser.searchResults();
        if (results.isEmpty()) {
            // Google sometimes shows a consent wall, a bot check, or simply has
            // not painted its result blocks yet. The page text is the next best
            // thing: better the model reads raw text than report a search that
            // looks like it never ran.
            String text = env.browser.text();
            if (text.isEmpty()) {
                // Deliberately not "try a different query": that invitation is
                // how a model burns its whole budget re-searching. It is told
                // the search failed and left to answer from knowledge instead.
                return "The search ran, but no readable results came back.";
            }
            return clamp("The search page gave no structured results, but its text says:\n" + text);
        }
        return clamp("Search results for \"" + query.trim() + "\":\n" + results);
    }

    /**
     * The GitHub token finally does something. It is read from the keystore for
     * this one call and never put in the prompt.
     */
    private static String githubFile(Env env, JSONObject args) throws Exception {
        String repo = args.optString("repo", "");
        String path = args.optString("path", "");
        String ref = args.optString("ref", "");
        if (repo.isEmpty() || path.isEmpty()) {
            return "Give me repo as owner/name and path as the file inside it.";
        }
        String token = env.vault == null ? "" : env.vault.get(SecretProvider.CORE, "github");
        String endpoint = "https://api.github.com/repos/" + repo + "/contents/" + path
            + (ref.isEmpty() ? "" : "?ref=" + ref);

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Luna");
            if (!token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            int code = connection.getResponseCode();
            if (code == 404) {
                return "GitHub says that file is not there" + (token.isEmpty()
                    ? " — and no token is saved, so a private repo would look the same." : ".");
            }
            if (code == 401 || code == 403) {
                return "GitHub refused the token (" + code + "). Check it in Settings.";
            }
            if (code != 200) {
                return "GitHub answered " + code + ".";
            }
            StringBuilder payload = new StringBuilder();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    payload.append(line);
                }
            } finally {
                WorkspaceStore.closeQuietly(reader);
            }
            JSONObject json = new JSONObject(payload.toString());
            String encoded = json.optString("content", "").replace("\n", "");
            if (encoded.isEmpty()) {
                return "That path is a folder, not a file.";
            }
            String decoded = new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8);
            return clamp(repo + "/" + path + ":\n" + decoded);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // --- git ------------------------------------------------------------------

    /** The same token the GitHub file tool uses, or empty for public access. */
    private static String gitToken(Env env) {
        return env.vault == null ? "" : env.vault.get(SecretProvider.CORE, "github");
    }

    private static String gitClone(Env env, String url, String name) {
        if (env.git == null) {
            return "There is no git available on this device.";
        }
        if (url == null || url.trim().isEmpty()) {
            return "Give me the repository address to clone.";
        }
        String refused = NetworkTargets.check(url);
        if (refused != null) {
            return "That repository address was refused: " + refused + ".";
        }
        String result = env.git.clone(url.trim(), name, gitToken(env));
        return result.isEmpty() ? "Cloned " + url.trim() + " into the git workspace." : result;
    }

    private static String gitPull(Env env, String path) {
        if (env.git == null) {
            return "There is no git available on this device.";
        }
        if (path == null || path.trim().isEmpty()) {
            return "Give me the repository to pull (the name it was cloned under).";
        }
        String result = env.git.pull(path.trim(), gitToken(env));
        return result.isEmpty() ? "Pulled " + path.trim() + "." : result;
    }

    private static String gitPush(Env env, String path) {
        if (env.git == null) {
            return "There is no git available on this device.";
        }
        if (path == null || path.trim().isEmpty()) {
            return "Give me the repository to push (the name it was cloned under).";
        }
        String result = env.git.push(path.trim(), gitToken(env));
        return result.isEmpty() ? "Pushed " + path.trim() + "." : result;
    }

    private static String gitStatus(Env env, String path) {
        if (env.git == null) {
            return "There is no git available on this device.";
        }
        return env.git.status(path == null ? "" : path.trim());
    }

    private static String gitCommit(Env env, String path, String message) {
        if (env.git == null) {
            return "There is no git available on this device.";
        }
        String result = env.git.commit(path == null ? "" : path.trim(), message);
        return result.isEmpty() ? "Committed the changes in " + path + "." : result;
    }

    private static String gitLog(Env env, String path) {
        if (env.git == null) {
            return "There is no git available on this device.";
        }
        return env.git.log(path == null ? "" : path.trim(), 10);
    }

    private static String gitDiff(Env env, String path) {
        if (env.git == null) {
            return "There is no git available on this device.";
        }
        return env.git.diff(path == null ? "" : path.trim());
    }

    // --- the folder --------------------------------------------------------------

    private static String listFiles(StorageProvider workspace, String path) throws Exception {
        if (workspace.rootState().equals(WorkspaceStore.STATE_REVOKED)) {
            return "The folder permission was withdrawn. Ask the user to grant it again in Files.";
        }
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

    private static String readFile(StorageProvider workspace, String path) throws Exception {
        String text = workspace.readText(path);
        return clamp(path + ":\n" + text);
    }

    private static String search(StorageProvider workspace, String query) throws Exception {
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
