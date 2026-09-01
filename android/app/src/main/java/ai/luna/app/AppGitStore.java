package ai.luna.app;

import ai.luna.contracts.GitProvider;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Git as a library, inside the app.
 *
 * <p>Android has no git binary to run, so JGit does the work in-process.
 * Repositories live under one private directory — separate from the granted
 * folder, whose SAF URIs a git library cannot open — and are named by the
 * caller. The remote is contacted over HTTPS with the user's GitHub token, the
 * same one the {@code github_file} tool reads from the keystore; an empty token
 * reaches public repositories.
 *
 * <p>Every public method answers in words, never by throwing: a failed pull and
 * a successful pull are both a sentence the model can read.
 */
public final class AppGitStore implements GitProvider {

    /** How much of a diff is worth reading into the model's context. */
    private static final int MAX_DIFF = 4000;

    /** How much of one commit message is worth showing. */
    private static final int MAX_SUBJECT = 80;

    private final File root;
    private final ErrorLog errors;

    public AppGitStore(File root, ErrorLog errors) {
        this.root = root == null ? new File(".") : root;
        this.errors = errors;
        if (!this.root.exists()) {
            this.root.mkdirs();
        }
    }

    @Override
    public String id() {
        return "app.git";
    }

    @Override
    public String clone(String url, String name, String token) {
        if (name == null || name.trim().isEmpty()) {
            name = nameFrom(url);
        }
        File dir = new File(root, sanitize(name));
        if (dir.exists()) {
            return "There is already something at \"" + name + "\" in the git workspace.";
        }
        try {
            org.eclipse.jgit.api.CloneCommand command = Git.cloneRepository()
                .setURI(url)
                .setDirectory(dir);
            if (!token.isEmpty()) {
                command.setCredentialsProvider(credential(token));
            }
            Git git = command.call();
            git.close();
            return "";
        } catch (Exception error) {
            return wrap("clone " + url, error);
        }
    }

    @Override
    public String pull(String path, String token) {
        File dir = repo(path);
        if (dir == null) {
            return noRepo(path);
        }
        try (Git git = Git.open(dir)) {
            org.eclipse.jgit.api.PullCommand command = git.pull();
            if (!token.isEmpty()) {
                command.setCredentialsProvider(credential(token));
            }
            org.eclipse.jgit.api.PullResult result = command.call();
            return result.isSuccessful() ? "" : "The pull could not be completed.";
        } catch (Exception error) {
            return wrap("pull " + path, error);
        }
    }

    @Override
    public String push(String path, String token) {
        File dir = repo(path);
        if (dir == null) {
            return noRepo(path);
        }
        try (Git git = Git.open(dir)) {
            org.eclipse.jgit.api.PushCommand command = git.push();
            if (!token.isEmpty()) {
                command.setCredentialsProvider(credential(token));
            }
            for (PushResult result : command.call()) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    if (update.getStatus() == RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD) {
                        return "The push was refused because the remote has moved. Pull first.";
                    }
                }
            }
            return "";
        } catch (Exception error) {
            return wrap("push " + path, error);
        }
    }

    @Override
    public String status(String path) {
        File dir = repo(path);
        if (dir == null) {
            return noRepo(path);
        }
        try (Git git = Git.open(dir)) {
            Status status = git.status().call();
            StringBuilder out = new StringBuilder();
            add(out, "added", status.getAdded());
            add(out, "modified", status.getModified());
            add(out, "deleted", status.getRemoved());
            add(out, "missing", status.getMissing());
            add(out, "untracked", status.getUntracked());
            return out.length() == 0 ? "Nothing has changed." : out.toString();
        } catch (Exception error) {
            return wrap("show the status of " + path, error);
        }
    }

    @Override
    public String commit(String path, String message) {
        File dir = repo(path);
        if (dir == null) {
            return noRepo(path);
        }
        if (message == null || message.trim().isEmpty()) {
            return "A commit needs a message.";
        }
        try (Git git = Git.open(dir)) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage(message.trim()).call();
            return "";
        } catch (Exception error) {
            return wrap("commit in " + path, error);
        }
    }

    @Override
    public String log(String path, int limit) {
        File dir = repo(path);
        if (dir == null) {
            return noRepo(path);
        }
        try (Git git = Git.open(dir)) {
            StringBuilder out = new StringBuilder();
            for (RevCommit commit : git.log().setMaxCount(limit <= 0 ? 10 : limit).call()) {
                out.append(commit.abbreviate(7).name()).append(' ')
                    .append(subject(commit.getShortMessage())).append('\n');
            }
            return out.length() == 0 ? "No commits yet." : out.toString();
        } catch (Exception error) {
            return wrap("show the history of " + path, error);
        }
    }

    @Override
    public String diff(String path) {
        File dir = repo(path);
        if (dir == null) {
            return noRepo(path);
        }
        try (Git git = Git.open(dir)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            List<DiffEntry> entries = git.diff().call();
            try (DiffFormatter formatter = new DiffFormatter(out)) {
                formatter.setRepository(git.getRepository());
                for (DiffEntry entry : entries) {
                    formatter.format(entry);
                }
                formatter.flush();
            }
            String text = out.toString(StandardCharsets.UTF_8.name());
            if (text.trim().isEmpty()) {
                return "No uncommitted changes.";
            }
            return ReadableText.clean(text, MAX_DIFF);
        } catch (Exception error) {
            return wrap("show the changes in " + path, error);
        }
    }

    // --- helpers --------------------------------------------------------------

    /** The repository directory for a name, or null when it is not a repo. */
    private File repo(String path) {
        if (path == null || path.trim().isEmpty()) {
            return null;
        }
        File dir = new File(root, sanitize(path));
        if (!dir.isDirectory()) {
            return null;
        }
        try (Git ignored = Git.open(dir)) {
            return dir;
        } catch (Exception error) {
            return null;
        }
    }

    private static String noRepo(String path) {
        return "There is no repository named \"" + path + "\" in the git workspace. "
            + "Clone one first.";
    }

    private static UsernamePasswordCredentialsProvider credential(String token) {
        return new UsernamePasswordCredentialsProvider(token, "");
    }

    private String wrap(String what, Exception error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        if (errors != null) {
            errors.warn("git", what + ": " + message);
        }
        return "Git could not " + what + " (" + ReadableText.clean(message, 160) + ").";
    }

    /** The folder name to use when the caller gave none: the last segment. */
    private static String nameFrom(String url) {
        String trimmed = url == null ? "" : url.trim();
        int cut = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf(':'));
        String segment = trimmed.substring(cut + 1);
        if (segment.endsWith(".git")) {
            segment = segment.substring(0, segment.length() - 4);
        }
        return segment.isEmpty() ? "repo" : segment;
    }

    /** One folder level, and nothing that could climb out of the root. */
    private static String sanitize(String name) {
        String clean = name.trim().replace('\\', '/');
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        clean = clean.replace("..", "-").replace("/", "-");
        return clean.isEmpty() ? "repo" : clean;
    }

    private static void add(StringBuilder out, String label, java.util.Set<String> paths) {
        for (String path : paths) {
            out.append(label).append(": ").append(path).append('\n');
        }
    }

    private static String subject(String message) {
        String first = message == null ? "" : message.split("\n", 2)[0].trim();
        return first.length() > MAX_SUBJECT ? first.substring(0, MAX_SUBJECT) + "…" : first;
    }
}
