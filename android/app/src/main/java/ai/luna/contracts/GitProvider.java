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
}
