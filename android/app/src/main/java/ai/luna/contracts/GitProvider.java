package ai.luna.contracts;

/**
 * A git client, wherever it lives.
 *
 * <p>On Android there is no git binary to shell out to, so git is a library
 * behind this contract. Repositories live in the provider's own workspace — on
 * the phone that is Luna's private storage, because the granted folder arrives
 * as SAF URIs which a git library cannot open. The remote is contacted with
 * the same GitHub token the file tool uses; an empty token means public access.
 *
 * <p>Mutating calls return empty on success and a plain refusal on failure;
 * read calls return the text to hand back to the model.
 */
public interface GitProvider {

    String id();

    /** Clone {@code url} under {@code name} (derived from the url when empty). */
    String clone(String url, String name, String token);

    String pull(String path, String token);

    String push(String path, String token);

    String status(String path);

    String commit(String path, String message);

    String log(String path, int limit);

    String diff(String path);

    // --- working tree ---------------------------------------------------------
    //
    // A clone nobody can read is a download, not a checkout. These are the
    // calls that let an agent actually work inside a repository: list it, read
    // a file, change one, and remove one. Paths are {@code repo/inner/path},
    // always relative to the provider's workspace, and never able to climb out
    // of the repository they name.

    /** Entries directly under {@code path} ({@code repo} or {@code repo/dir}). */
    String list(String path);

    /** The text of one file, as {@code repo/inner/path}. */
    String read(String path);

    /** Write {@code content} to {@code repo/inner/path}, creating parents. */
    String write(String path, String content);

    /** Make an empty file, or a folder when {@code folder} is true. */
    String create(String path, boolean folder);

    /** Remove a file or a folder and everything under it. */
    String delete(String path);

    /** Move or rename, both {@code repo/inner/path}. */
    String move(String from, String to);
}
