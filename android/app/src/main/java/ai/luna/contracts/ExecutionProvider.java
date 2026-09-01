package ai.luna.contracts;

import java.util.List;

/**
 * Where a tool actually runs.
 *
 * <p>Deliberately separate from {@link InferenceProvider}: thinking and doing
 * are different questions. A phone can hold the conversation while a VPS holds
 * the checkout, or a cloud model can drive a sandbox that has no phone in it at
 * all. An environment is one of these plus the storage, browser and secrets it
 * exposes.
 */
public interface ExecutionProvider {

    /** {@code android.local}, {@code docker}, {@code vps.ssh}, … */
    String id();

    String displayName();

    /** android, desktop, server. Tools declare which they support. */
    String platform();

    /** Is this the device the person is holding? */
    boolean local();

    /** Capability names this environment is able to offer at all. */
    List<String> capabilities();

    StorageProvider storage();

    /** May be null: not every environment has a browser. */
    BrowserProvider browser();

    SecretProvider secrets();

    /**
     * A git client, or null when this environment has none. The phone offers
     * git through JGit; a server environment could offer a real binary behind
     * the same contract.
     */
    default GitProvider git() {
        return null;
    }

    /** True when the environment is reachable right now. */
    boolean available();

    /** Why it is not usable, in plain words, or null when it is. */
    String problem();
}
