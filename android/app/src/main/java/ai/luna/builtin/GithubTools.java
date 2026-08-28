package ai.luna.builtin;

import ai.luna.contracts.Capability;
import ai.luna.contracts.RiskLevel;
import ai.luna.contracts.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Code hosting.
 *
 * <p>This is the provider that will leave the core first: everything it does is
 * a network request plus a saved token, which is exactly what a plugin is
 * allowed to be. It sits here so the seam it will leave through is already
 * built.
 */
public final class GithubTools extends BuiltinProvider {

    public GithubTools() {
        super(declare());
    }

    @Override
    public String id() {
        return "core.github";
    }

    private static List<ToolDefinition> declare() {
        List<ToolDefinition> all = new ArrayList<>();
        all.add(ToolDefinition.of("github_file", "Read from GitHub")
            .description("One file out of a repository")
            .input("repo", "owner/name", "path", "Path in the repository")
            .required("repo", "path")
            .capabilities(Capability.GITHUB_READ, Capability.NETWORK_REQUEST,
                Capability.CREDENTIAL_READ)
            .risk(RiskLevel.MEDIUM)
            .build());
        return all;
    }
}
