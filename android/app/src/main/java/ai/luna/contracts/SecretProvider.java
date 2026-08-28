package ai.luna.contracts;

/**
 * Keys, held for someone else.
 *
 * <p>Two rules make this safe to expose to plugins. Secrets are namespaced by
 * owner, so one plugin cannot read another's; and there is no method that
 * returns a secret to a caller that is not its owner — a tool asks the runtime
 * to <em>use</em> a key, it does not ask to see one.
 */
public interface SecretProvider {

    String id();

    /** Store {@code value} under {@code key} for {@code owner}. */
    void put(String owner, String key, String value) throws Exception;

    /** The value, or empty. Only the owner may read its own namespace. */
    String get(String owner, String key);

    boolean has(String owner, String key);

    void remove(String owner, String key);

    /** The reserved owner for the app's own secrets. */
    String CORE = "core";
}
