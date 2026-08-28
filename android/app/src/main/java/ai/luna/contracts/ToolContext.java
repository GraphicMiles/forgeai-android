package ai.luna.contracts;

/**
 * Everything a tool is allowed to touch, handed in rather than looked up.
 *
 * <p>This is Luna's existing {@code Tools.Env} promoted to a contract. The
 * difference that matters: it carries the id of the agent making the call and
 * the id of the plugin that owns the tool, so a secret, a budget or a refusal
 * can be attributed to somebody. A tool reaches the device through this object
 * and through nothing else.
 */
public final class ToolContext {

    public final String agentId;

    /** Who owns the tool being run. {@code core} for built-ins. */
    public final String ownerId;

    public final StorageProvider storage;
    public final BrowserProvider browser;
    public final SecretProvider secrets;
    public final Trace trace;

    /** Where this call is running: android, desktop, server. */
    public final String platform;

    public ToolContext(String agentId, String ownerId, StorageProvider storage,
                       BrowserProvider browser, SecretProvider secrets, Trace trace,
                       String platform) {
        this.agentId = agentId == null || agentId.isEmpty() ? "luna" : agentId;
        this.ownerId = ownerId == null || ownerId.isEmpty() ? "core" : ownerId;
        this.storage = storage;
        this.browser = browser;
        this.secrets = secrets;
        this.trace = trace == null ? Trace.SILENT : trace;
        this.platform = platform == null || platform.isEmpty() ? "android" : platform;
    }

    /** The same context, credited to a different owner. */
    public ToolContext ownedBy(String owner) {
        return new ToolContext(agentId, owner, storage, browser, secrets, trace, platform);
    }

    public boolean hasStorage() {
        return storage != null && storage.hasRoot();
    }

    public boolean hasBrowser() {
        return browser != null;
    }
}
