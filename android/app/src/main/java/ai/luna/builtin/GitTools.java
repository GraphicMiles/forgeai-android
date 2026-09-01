package ai.luna.builtin;

import ai.luna.contracts.Capability;
import ai.luna.contracts.RiskLevel;
import ai.luna.contracts.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Git, run by JGit inside the app against Luna's own repository workspace.
 *
 * <p>The phone cannot run a native git binary, so git is a library rather
 * than a command. The repositories live in a private git workspace — not in
 * the granted folder, which Android hands over as SAF URIs JGit cannot open.
 * The remote is contacted with the same GitHub token the file tool uses.
 */
public final class GitTools extends BuiltinProvider {

    public GitTools() {
        super(declare());
    }

    @Override
    public String id() {
        return "core.git";
    }

    private static List<ToolDefinition> declare() {
        List<ToolDefinition> all = new ArrayList<>();
        all.add(ToolDefinition.of("git_clone", "Clone a repository")
            .description("Download a repository into Luna's git workspace")
            .input("url", "The repository address", "name", "Folder name, or empty to use the repo name")
            .required("url")
            .capabilities(Capability.NETWORK_REQUEST, Capability.GITHUB_READ,
                Capability.FILESYSTEM_WRITE)
            .risk(RiskLevel.MEDIUM)
            .timeout(60000L)
            .requires("git")
            .build());
        all.add(ToolDefinition.of("git_pull", "Pull a repository")
            .description("Fetch the newest changes from a repository's remote")
            .input("path", "Repository, by the name it was cloned under")
            .required("path")
            .capabilities(Capability.NETWORK_REQUEST, Capability.GITHUB_READ,
                Capability.FILESYSTEM_WRITE)
            .risk(RiskLevel.MEDIUM)
            .timeout(60000L)
            .requires("git")
            .build());
        all.add(ToolDefinition.of("git_push", "Push a repository")
            .description("Send local commits to a repository's remote")
            .input("path", "Repository, by the name it was cloned under")
            .required("path")
            .capabilities(Capability.NETWORK_REQUEST, Capability.GITHUB_WRITE,
                Capability.FILESYSTEM_READ)
            .risk(RiskLevel.MEDIUM)
            .timeout(60000L)
            .requires("git")
            .build());
        all.add(ToolDefinition.of("git_status", "Git status")
            .description("What has changed in a repository")
            .input("path", "Repository, by the name it was cloned under")
            .required("path")
            .capabilities(Capability.FILESYSTEM_READ)
            .risk(RiskLevel.LOW)
            .timeout(15000L)
            .requires("git")
            .build());
        all.add(ToolDefinition.of("git_commit", "Commit changes")
            .description("Record the current changes as a commit")
            .input("path", "Repository, by the name it was cloned under", "message",
                "The commit message")
            .required("path", "message")
            .capabilities(Capability.FILESYSTEM_WRITE)
            .risk(RiskLevel.MEDIUM)
            .timeout(30000L)
            .requires("git")
            .build());
        all.add(ToolDefinition.of("git_log", "Git history")
            .description("The recent commits of a repository")
            .input("path", "Repository, by the name it was cloned under")
            .required("path")
            .capabilities(Capability.FILESYSTEM_READ)
            .risk(RiskLevel.LOW)
            .timeout(15000L)
            .requires("git")
            .build());
        all.add(ToolDefinition.of("git_diff", "Git diff")
            .description("The uncommitted changes of a repository")
            .input("path", "Repository, by the name it was cloned under")
            .required("path")
            .capabilities(Capability.FILESYSTEM_READ)
            .risk(RiskLevel.LOW)
            .timeout(15000L)
            .requires("git")
            .build());
        return all;
    }
}
