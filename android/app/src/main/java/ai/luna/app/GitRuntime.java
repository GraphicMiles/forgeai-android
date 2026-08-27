package ai.luna.app;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

@CapacitorPlugin(name = "GitRuntime")
public class GitRuntime extends Plugin {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private void requireAutonomy() {
        if (!AutonomyRuntime.isEnabled(getContext())) throw new IllegalArgumentException("Full Autonomous mode is disabled.");
    }

    private CredentialsProvider credentials() throws Exception {
        String token = CredentialVault.getToken(getContext());
        return token.isEmpty() ? null : new UsernamePasswordCredentialsProvider("x-access-token", token);
    }

    private File repositoriesRoot() throws Exception {
        File root = new File(getContext().getFilesDir(), "repositories").getCanonicalFile();
        if (!root.exists() && !root.mkdirs()) throw new IllegalArgumentException("Unable to create repository storage.");
        return root;
    }

    private File safeRepository(String path) throws Exception {
        File root = repositoriesRoot();
        File repository = new File(path).getCanonicalFile();
        if (!repository.toPath().startsWith(root.toPath()) || !new File(repository, ".git").isDirectory()) throw new IllegalArgumentException("Git repository must be a Luna app-private clone.");
        return repository;
    }

    private String[] parseRepo(String value) {
        String cleaned = value.trim().replace("https://github.com/", "").replaceAll("\\.git$", "");
        String[] parts = cleaned.split("/");
        if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_.-]+") || !parts[1].matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("Repository must be owner/name or a github.com URL.");
        return parts;
    }

    @PluginMethod
    public void cloneRepository(PluginCall call) {
        executor.execute(() -> {
            try {
                requireAutonomy();
                String[] repo = parseRepo(call.getString("repository", ""));
                String branch = call.getString("branch", "").trim();
                File target = new File(repositoriesRoot(), repo[0] + "-" + repo[1] + "-" + UUID.randomUUID()).getCanonicalFile();
                org.eclipse.jgit.api.CloneCommand command = Git.cloneRepository()
                    .setURI("https://github.com/" + repo[0] + "/" + repo[1] + ".git")
                    .setDirectory(target)
                    .setCloneAllBranches(false);
                if (!branch.isEmpty()) command.setBranch(branch);
                CredentialsProvider provider = credentials(); if (provider != null) command.setCredentialsProvider(provider);
                try (Git git = command.call()) {
                    JSObject result = new JSObject(); result.put("repository", repo[0] + "/" + repo[1]); result.put("path", target.getAbsolutePath()); result.put("branch", git.getRepository().getBranch()); result.put("head", git.getRepository().resolve("HEAD").name()); call.resolve(result);
                }
            } catch (Exception error) { call.reject(error.getMessage(), "GIT_CLONE_FAILED"); }
        });
    }

    @PluginMethod
    public void status(PluginCall call) {
        executor.execute(() -> {
            try (Git git = Git.open(safeRepository(call.getString("path", "")))) {
                Status status = git.status().call();
                JSObject result = new JSObject(); result.put("branch", git.getRepository().getBranch()); result.put("clean", status.isClean());
                for (String key : new String[] { "added", "changed", "modified", "missing", "removed", "untracked", "conflicting" }) {
                    java.util.Set<String> values;
                    switch (key) {
                        case "added": values = status.getAdded(); break; case "changed": values = status.getChanged(); break;
                        case "modified": values = status.getModified(); break; case "missing": values = status.getMissing(); break;
                        case "removed": values = status.getRemoved(); break; case "untracked": values = status.getUntracked(); break;
                        default: values = status.getConflicting();
                    }
                    JSArray array = new JSArray(); for (String value : values) array.put(value); result.put(key, array);
                }
                call.resolve(result);
            } catch (Exception error) { call.reject(error.getMessage(), "GIT_STATUS_FAILED"); }
        });
    }

    @PluginMethod
    public void log(PluginCall call) {
        executor.execute(() -> {
            try (Git git = Git.open(safeRepository(call.getString("path", "")))) {
                int max = Math.min(Math.max(call.getInt("max", 20), 1), 100);
                JSArray commits = new JSArray();
                int count = 0;
                for (RevCommit commit : git.log().setMaxCount(max).call()) {
                    JSObject item = new JSObject(); item.put("id", commit.getId().name()); item.put("shortId", commit.getId().abbreviate(8).name()); item.put("message", commit.getFullMessage()); item.put("author", commit.getAuthorIdent().getName()); item.put("time", commit.getCommitTime() * 1000L); commits.put(item); if (++count >= max) break;
                }
                JSObject result = new JSObject(); result.put("commits", commits); call.resolve(result);
            } catch (Exception error) { call.reject(error.getMessage(), "GIT_LOG_FAILED"); }
        });
    }

    @PluginMethod
    public void fetch(PluginCall call) {
        executor.execute(() -> {
            try (Git git = Git.open(safeRepository(call.getString("path", "")))) {
                requireAutonomy(); org.eclipse.jgit.api.FetchCommand command = git.fetch(); CredentialsProvider provider = credentials(); if (provider != null) command.setCredentialsProvider(provider); command.call(); call.resolve();
            } catch (Exception error) { call.reject(error.getMessage(), "GIT_FETCH_FAILED"); }
        });
    }

    @PluginMethod
    public void pull(PluginCall call) {
        executor.execute(() -> {
            try (Git git = Git.open(safeRepository(call.getString("path", "")))) {
                requireAutonomy(); org.eclipse.jgit.api.PullCommand command = git.pull(); CredentialsProvider provider = credentials(); if (provider != null) command.setCredentialsProvider(provider); org.eclipse.jgit.api.PullResult pulled = command.call(); JSObject result = new JSObject(); result.put("successful", pulled.isSuccessful()); result.put("result", pulled.toString()); call.resolve(result);
            } catch (Exception error) { call.reject(error.getMessage(), "GIT_PULL_FAILED"); }
        });
    }

    @PluginMethod
    public void checkout(PluginCall call) {
        executor.execute(() -> {
            try (Git git = Git.open(safeRepository(call.getString("path", "")))) {
                requireAutonomy(); String branch = call.getString("branch", ""); boolean create = call.getBoolean("create", false); org.eclipse.jgit.api.CheckoutCommand command = git.checkout().setName(branch).setCreateBranch(create); if (create) command.setStartPoint(call.getString("startPoint", "HEAD")); Ref ref = command.call(); JSObject result = new JSObject(); result.put("branch", git.getRepository().getBranch()); result.put("ref", ref == null ? "" : ref.getName()); call.resolve(result);
            } catch (Exception error) { call.reject(error.getMessage(), "GIT_CHECKOUT_FAILED"); }
        });
    }

    @PluginMethod
    public void commit(PluginCall call) {
        executor.execute(() -> {
            try (Git git = Git.open(safeRepository(call.getString("path", "")))) {
                requireAutonomy(); String message = call.getString("message", "").trim(); if (message.isEmpty()) throw new IllegalArgumentException("Commit message is required.");
                git.add().addFilepattern(".").call(); git.add().setUpdate(true).addFilepattern(".").call();
                RevCommit commit = git.commit().setMessage(message).setAuthor(call.getString("authorName", "Luna User"), call.getString("authorEmail", "luna@localhost")).call();
                JSObject result = new JSObject(); result.put("id", commit.getId().name()); result.put("message", commit.getFullMessage()); call.resolve(result);
            } catch (Exception error) { call.reject(error.getMessage(), "GIT_COMMIT_FAILED"); }
        });
    }

    @PluginMethod
    public void push(PluginCall call) {
        executor.execute(() -> {
            try (Git git = Git.open(safeRepository(call.getString("path", "")))) {
                requireAutonomy(); org.eclipse.jgit.api.PushCommand command = git.push(); CredentialsProvider provider = credentials(); if (provider != null) command.setCredentialsProvider(provider); command.setForce(call.getBoolean("force", false)); Iterable<org.eclipse.jgit.transport.PushResult> pushed = command.call(); JSArray messages = new JSArray(); for (org.eclipse.jgit.transport.PushResult result : pushed) messages.put(result.getMessages()); JSObject response = new JSObject(); response.put("messages", messages); call.resolve(response);
            } catch (Exception error) { call.reject(error.getMessage(), "GIT_PUSH_FAILED"); }
        });
    }

    @PluginMethod
    public void rebase(PluginCall call) {
        executor.execute(() -> {
            try (Git git = Git.open(safeRepository(call.getString("path", "")))) {
                requireAutonomy(); String upstream = call.getString("upstream", "").trim(); if (upstream.isEmpty()) throw new IllegalArgumentException("Rebase upstream is required."); org.eclipse.jgit.api.RebaseResult rebased = git.rebase().setUpstream(upstream).call(); JSObject result = new JSObject(); result.put("status", rebased.getStatus().toString()); result.put("successful", rebased.getStatus().isSuccessful()); call.resolve(result);
            } catch (Exception error) { call.reject(error.getMessage(), "GIT_REBASE_FAILED"); }
        });
    }
}
