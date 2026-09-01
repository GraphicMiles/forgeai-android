package ai.luna.app;

import ai.luna.contracts.BrowserProvider;
import ai.luna.contracts.Capability;
import ai.luna.contracts.ExecutionProvider;
import ai.luna.contracts.GitProvider;
import ai.luna.contracts.SecretProvider;
import ai.luna.contracts.StorageProvider;

import java.util.Arrays;
import java.util.List;

/**
 * This phone, as somewhere tools can run.
 *
 * <p>Inference asks "where does it think"; execution asks "where does it act".
 * Today both answers are the phone, which is exactly why the distinction has to
 * be written down now: a VPS environment is another implementation of this
 * interface and nothing above it should have to notice.
 */
public final class AndroidExecution implements ExecutionProvider {

    private final WorkspaceStore workspace;
    private final HeadlessBrowser browser;
    private final CredentialVault vault;
    private final GitProvider git;

    public AndroidExecution(WorkspaceStore workspace, HeadlessBrowser browser,
                            CredentialVault vault, GitProvider git) {
        this.workspace = workspace;
        this.browser = browser;
        this.vault = vault;
        this.git = git;
    }

    @Override
    public String id() {
        return "android.local";
    }

    @Override
    public String displayName() {
        return "This phone";
    }

    @Override
    public String platform() {
        return "android";
    }

    @Override
    public boolean local() {
        return true;
    }

    /** No shell and no process spawning: Android does not give us either. */
    @Override
    public List<String> capabilities() {
        return Arrays.asList(
            Capability.FILESYSTEM_READ,
            Capability.FILESYSTEM_WRITE,
            Capability.FILESYSTEM_DELETE,
            Capability.NETWORK_REQUEST,
            Capability.BROWSER_NAVIGATE,
            Capability.BROWSER_READ,
            Capability.GITHUB_READ,
            Capability.GITHUB_WRITE,
            Capability.CREDENTIAL_READ,
            Capability.USER_ASK);
    }

    @Override
    public StorageProvider storage() {
        return workspace;
    }

    @Override
    public BrowserProvider browser() {
        return browser;
    }

    @Override
    public SecretProvider secrets() {
        return vault;
    }

    @Override
    public GitProvider git() {
        return git;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String problem() {
        return workspace != null && workspace.hasRoot()
            ? null
            : "No folder is granted yet, so the file tools cannot run here.";
    }
}
