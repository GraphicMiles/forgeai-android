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

    /**
     * Search results are capped harder than file content.
     *
     * <p>A page of results is a list of titles and snippets, and the tail of it
     * is chrome -- related searches, footers, cookie notices. File content is
     * not: the end of a file matters as much as the start. Two searches at the
     * full 4000 sent a 6338-token request into an 8000-per-minute limit and
     * rate-limited the run that was trying to answer.
     */
    private static final int MAX_SEARCH_OBSERVATION = 2000;

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

    /**
     * When the file did not land where it was asked for, the model has to be
     * told in words it cannot skim past, or it reports the path it requested
     * and the person is told something untrue.
     */
    /**
     * How much was written, in the terms the caller used.
     *
     * <p>The point is that the model can tell an empty write from a full one
     * without reading the file back, so a successful write is never repeated
     * out of doubt.
     */
    private static String measured(String content) {
        if (content == null || content.isEmpty()) {
            return "an empty file";
        }
        int lines = content.split("\n", -1).length;
        int chars = content.length();
        return lines + (lines == 1 ? " line" : " lines") + " (" + chars
            + (chars == 1 ? " character)" : " characters)");
    }

    private static String landedNote(String asked, String actual) {
        if (asked == null || actual == null || asked.isEmpty() || asked.equals(actual)) {
            return "";
        }
        return " Note: the path is " + actual + ", not " + asked
            + ". Tell the user the real path.";
    }

    /** The last segment of a path, which is the part a rename was asked about. */
    private static String baseName(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
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
                case "write_file": {
                    // Report where the bytes went, not where they were aimed,
                    // and how many there were. "Wrote youtube.yaml." reads the
                    // same whether the content arrived or an empty string did,
                    // so a model that is unsure whether its first write landed
                    // has nothing to check and writes the file a second time.
                    String asked = args.optString("path", "");
                    String content = args.optString("content", "");
                    String wrote = env.workspace.writeText(asked, content);
                    return "Wrote " + measured(content) + " to " + wrote + "."
                        + landedNote(asked, wrote);
                }
                case "edit_file": {
                    return editFile(env, args.optString("path", ""),
                        args.optString("find", args.optString("search", "")),
                        args.optString("replace", args.optString("replacement", "")),
                        args.optBoolean("all", false));
                }
                case "git_edit": {
                    return gitEdit(env, args.optString("path", ""),
                        args.optString("find", args.optString("search", "")),
                        args.optString("replace", args.optString("replacement", "")),
                        args.optBoolean("all", false));
                }
                case "create_file": {
                    String asked = args.optString("path", "");
                    String created = env.workspace.createFile(asked);
                    return "Created " + created + "." + landedNote(asked, created);
                }
                case "create_folder":
                    env.workspace.createFolder(args.optString("path", ""));
                    return "Created the folder " + args.optString("path", "") + ".";
                case "delete_file":
                    env.workspace.delete(args.optString("path", ""));
                    return "Deleted " + args.optString("path", "") + ". A backup was kept.";
                case "rename_file": {
                    // A rename is given a bare name but reports a full path, so
                    // only the name itself is worth comparing.
                    String asked = args.optString("newName", "");
                    String renamed = env.workspace.rename(args.optString("path", ""), asked);
                    return "Renamed to " + renamed + "." + landedNote(asked, baseName(renamed));
                }
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
                case "git_list":
                    return gitList(env, args.optString("path", ""));
                case "git_read":
                    return gitRead(env, args.optString("path", ""));
                case "git_write":
                    return gitWrite(env, args.optString("path", ""),
                        args.optString("content", ""));
                case "git_create":
                    return gitCreate(env, args.optString("path", ""),
                        args.optBoolean("folder", false));
                case "git_delete":
                    return gitDelete(env, args.optString("path", ""));
                case "git_move":
                    return gitMove(env, args.optString("path", ""),
                        args.optString("to", args.optString("newName", "")));
                default:
                    return Recovery.unknownTool(tool, ai.luna.builtin.Builtins.ids());
            }
        } catch (Exception error) {
            // The log keeps the real message; the model gets advice it can act on.
            String message = error.getMessage();
            if (env.errors != null) {
                env.errors.fail(tool, message == null ? String.valueOf(error) : message);
            }
            return Recovery.from(tool, error);
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
        // A title is not what a page is about. Left with only this, a model
        // will describe the site from its name and call the job done, so the
        // next step is stated as the only one available.
        return "Opened " + env.browser.currentUrl() + (title.isEmpty() ? "" : " — " + title)
            + ". You have not read it yet and know nothing about its contents. Call read_page "
            + "next; do not describe the site until you have.";
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
    /**
     * The search tab a query is really asking for.
     *
     * <p>"pictures of a red kite" wants the image tab and "the latest MrBeast
     * video" wants the video one. Asking the web tab for either gets ten blue
     * links and nothing to look at. Only plain, unambiguous words count: a
     * query merely mentioning a photograph is still a web search.
     */
    private static String mediaTab(String query) {
        String text = query.toLowerCase(java.util.Locale.US);
        String[] pictures = {
            "picture", "pictures", "photo", "photos", "photograph", "image",
            "images", "wallpaper", "logo", "poster", "screenshot", "artwork",
            "what does it look like", "what does he look like",
            "what does she look like", "show me",
        };
        String[] moving = { "video", "videos", "trailer", "clip", "clips", "footage" };
        for (String word : moving) {
            if (containsWord(text, word)) {
                return "&tbm=vid";
            }
        }
        for (String word : pictures) {
            if (containsWord(text, word)) {
                return "&tbm=isch";
            }
        }
        return "";
    }

    /** Whole words only: "videographer" is not a request for video. */
    private static boolean containsWord(String text, String word) {
        int at = text.indexOf(word);
        while (at >= 0) {
            boolean beforeOk = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            int after = at + word.length();
            boolean afterOk = after >= text.length()
                || !Character.isLetterOrDigit(text.charAt(after));
            if (beforeOk && afterOk) {
                return true;
            }
            at = text.indexOf(word, at + 1);
        }
        return false;
    }

    private static String searchWeb(Env env, String query) throws Exception {
        if (env.browser == null) {
            return "There is no browser available on this device.";
        }
        if (query == null || query.trim().isEmpty()) {
            return "Give me a query to search for.";
        }
        // A search for pictures has to land on the pictures tab, or the page
        // has no pictures on it to find. Google's tbm switch is what the tab
        // buttons themselves use.
        String tab = mediaTab(query);
        String url = "https://www.google.com/search?q="
            + URLEncoder.encode(query.trim(), "UTF-8") + "&hl=en&num=10" + tab;
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
                // "Failed: " is load-bearing — it is what turns this into a
                // failed step rather than a tick in the trace. A search that
                // returned nothing must never look like a search that worked.
                return "Failed: the search ran but nothing readable came back, so you have no "
                    + "results. You do not know the answer. Tell the user the search returned "
                    + "nothing rather than answering from memory — anything you recall about "
                    + "recent or current events is out of date and would be a guess.";
            }
            return clamp("The search page gave no structured results, and the raw page text "
                + "below may be a consent wall or a bot check rather than an answer. Use it "
                + "only if it plainly contains what was asked; otherwise say the search did "
                + "not return results. Page text:\n" + text, MAX_SEARCH_OBSERVATION);
        }
        return clamp("Search results for \"" + query.trim() + "\". Answer only from what is "
            + "written here, and cite the page you took it from; if these results do not "
            + "contain the answer, say so rather than filling it in from memory.\n" + results,
            MAX_SEARCH_OBSERVATION);
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

    /** Every working-tree tool needs the same two checks before it can run. */
    /**
     * Change part of a file, leaving the rest of it alone.
     *
     * <p>The observation deliberately says which strategy matched. A model that
     * learns its exact block did not match, and only landed by anchoring, is a
     * model that copies more carefully next time.
     */
    private static String editFile(Env env, String path, String find, String replace,
                                   boolean all) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            return "Give me the file to edit.";
        }
        String before = env.workspace.readText(path);
        TextEdit.Result edit = TextEdit.apply(before, find, replace, all);
        if (!edit.ok) {
            return "Failed: " + edit.problem;
        }
        String landed = env.workspace.writeText(path, edit.text);
        return "Edited " + landed + " (" + edit.how + " match)."
            + landedNote(path, landed);
    }

    private static String gitEdit(Env env, String path, String find, String replace,
                                  boolean all) {
        String refused = gitGuard(env, path);
        if (refused != null) {
            return refused;
        }
        String before = env.git.read(path.trim());
        // read() answers in words when it cannot read, and those sentences are
        // not file contents to be edited.
        if (before.startsWith("There is no file at ") || before.startsWith("Give me a path")
            || before.startsWith("There is no repository named ")
            || before.startsWith("Paths have to stay inside")) {
            return before;
        }
        TextEdit.Result edit = TextEdit.apply(before, find, replace, all);
        if (!edit.ok) {
            return "Failed: " + edit.problem;
        }
        String result = env.git.write(path.trim(), edit.text);
        return result.isEmpty()
            ? "Edited " + path.trim() + " (" + edit.how + " match)." : result;
    }

    private static String gitGuard(Env env, String path) {
        if (env.git == null) {
            return "There is no git available on this device.";
        }
        if (path == null || path.trim().isEmpty()) {
            return "Give me a path as repository/file.";
        }
        return null;
    }

    private static String gitList(Env env, String path) {
        String refused = gitGuard(env, path);
        return refused != null ? refused : env.git.list(path.trim());
    }

    private static String gitRead(Env env, String path) {
        String refused = gitGuard(env, path);
        return refused != null ? refused : env.git.read(path.trim());
    }

    private static String gitWrite(Env env, String path, String content) {
        String refused = gitGuard(env, path);
        if (refused != null) {
            return refused;
        }
        String result = env.git.write(path.trim(), content);
        return result.isEmpty() ? "Wrote " + path.trim() + "." : result;
    }

    private static String gitCreate(Env env, String path, boolean folder) {
        String refused = gitGuard(env, path);
        if (refused != null) {
            return refused;
        }
        String result = env.git.create(path.trim(), folder);
        return result.isEmpty()
            ? "Created " + (folder ? "the folder " : "") + path.trim() + "." : result;
    }

    private static String gitDelete(Env env, String path) {
        String refused = gitGuard(env, path);
        if (refused != null) {
            return refused;
        }
        String result = env.git.delete(path.trim());
        return result.isEmpty() ? "Deleted " + path.trim() + "." : result;
    }

    private static String gitMove(Env env, String from, String to) {
        String refused = gitGuard(env, from);
        if (refused != null) {
            return refused;
        }
        if (to == null || to.trim().isEmpty()) {
            return "Give me where to move it to.";
        }
        String result = env.git.move(from.trim(), to.trim());
        return result.isEmpty() ? "Moved " + from.trim() + " to " + to.trim() + "." : result;
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
            // "Empty" is an answer a model will happily build a story on top
            // of. A folder that is not there is a different fact from a folder
            // with nothing in it, and the difference has to survive the trip.
            if (!path.isEmpty() && !workspace.exists(path)) {
                return "There is nothing called \"" + path + "\" in the granted folder. "
                    + "Cloned repositories live in Luna's git workspace, which the file tools "
                    + "cannot see. Do not describe files you have not read.";
            }
            return "The folder " + (path.isEmpty() ? "that was granted" : path)
                + " is empty. There are no files here to describe.";
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
            return "No matches for " + query + " anywhere in the granted folder. Note this "
                + "searches only the granted folder, not Luna's git workspace, so a cloned "
                + "repository will never match. Do not describe files you have not read.";
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
        return clamp(value, MAX_OBSERVATION);
    }

    static String clamp(String value, int limit) {
        if (value == null || value.length() <= limit) {
            return value;
        }
        return value.substring(0, limit)
            + "\n… truncated, read a smaller range if you need more.";
    }
}
