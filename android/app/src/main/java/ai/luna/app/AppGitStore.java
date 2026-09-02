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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

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

    // --- working tree ---------------------------------------------------------

    /** How much of one file is worth reading into the model's context. */
    private static final int MAX_READ = 24000;

    /** A refusal to write more than the phone should hold in one file. */
    private static final long MAX_WRITE = 2L * 1024L * 1024L;

    /** How many entries one listing may name before it stops being readable. */
    private static final int MAX_ENTRIES = 200;

    @Override
    public String list(String path) {
        Resolved target = resolve(path);
        if (target.refusal != null) {
            return target.refusal;
        }
        File dir = target.file;
        if (!dir.exists()) {
            return "There is nothing at \"" + path + "\".";
        }
        if (!dir.isDirectory()) {
            return "\"" + path + "\" is a file, not a folder. Read it instead.";
        }
        File[] children = dir.listFiles();
        if (children == null || children.length == 0) {
            return "The folder \"" + path + "\" is empty.";
        }
        Arrays.sort(children, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                boolean leftDir = left.isDirectory();
                if (leftDir != right.isDirectory()) {
                    return leftDir ? -1 : 1;
                }
                return left.getName().compareToIgnoreCase(right.getName());
            }
        });
        StringBuilder out = new StringBuilder();
        out.append(children.length).append(" items in ").append(path).append(":\n");
        for (int index = 0; index < children.length && index < MAX_ENTRIES; index++) {
            File child = children[index];
            // .git is the repository's own plumbing. Listing it invites the
            // model to read objects it cannot use and config it must not.
            if (child.getName().equals(".git")) {
                continue;
            }
            out.append(child.isDirectory() ? "dir  " : "file ").append(child.getName());
            if (!child.isDirectory()) {
                out.append("  ").append(child.length()).append(" B");
            }
            out.append('\n');
        }
        if (children.length > MAX_ENTRIES) {
            out.append("(").append(children.length - MAX_ENTRIES).append(" more)\n");
        }
        return out.toString();
    }

    @Override
    public String read(String path) {
        Resolved target = resolve(path);
        if (target.refusal != null) {
            return target.refusal;
        }
        File file = target.file;
        if (!file.exists()) {
            return "There is no file at \"" + path + "\".";
        }
        if (file.isDirectory()) {
            return "\"" + path + "\" is a folder. List it instead.";
        }
        if (file.length() > MAX_WRITE) {
            return "That file is larger than the 2 MB limit.";
        }
        try {
            byte[] bytes = readAll(file);
            if (looksBinary(bytes)) {
                return "\"" + path + "\" is a binary file (" + bytes.length
                    + " bytes), so there is no text to read.";
            }
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.length() > MAX_READ) {
                return text.substring(0, MAX_READ)
                    + "\n… (truncated at " + MAX_READ + " characters)";
            }
            return text;
        } catch (Exception error) {
            return wrap("read " + path, error);
        }
    }

    @Override
    public String write(String path, String content) {
        Resolved target = resolve(path);
        if (target.refusal != null) {
            return target.refusal;
        }
        String body = content == null ? "" : content;
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE) {
            return "That write is larger than the 2 MB cap.";
        }
        File file = target.file;
        if (file.isDirectory()) {
            return "\"" + path + "\" is a folder, so it cannot be written to.";
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return "Could not make the folder for " + path + ".";
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(bytes);
            out.flush();
        } catch (Exception error) {
            return wrap("write " + path, error);
        } finally {
            closeQuietly(out);
        }
        return "";
    }

    @Override
    public String create(String path, boolean folder) {
        Resolved target = resolve(path);
        if (target.refusal != null) {
            return target.refusal;
        }
        File file = target.file;
        if (file.exists()) {
            return "There is already something at \"" + path + "\".";
        }
        try {
            if (folder) {
                return file.mkdirs() ? "" : "Could not create the folder " + path + ".";
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return "Could not make the folder for " + path + ".";
            }
            return file.createNewFile() ? "" : "Could not create " + path + ".";
        } catch (Exception error) {
            return wrap("create " + path, error);
        }
    }

    @Override
    public String delete(String path) {
        Resolved target = resolve(path);
        if (target.refusal != null) {
            return target.refusal;
        }
        File file = target.file;
        if (target.inner.isEmpty()) {
            // Deleting the repository root through a file tool would be a
            // surprise; removing a clone is its own decision.
            return "That is the repository itself, not a file in it.";
        }
        if (!file.exists()) {
            return "There is nothing at \"" + path + "\".";
        }
        return removeTree(file) ? "" : "Could not delete " + path + ".";
    }

    @Override
    public String move(String from, String to) {
        Resolved source = resolve(from);
        if (source.refusal != null) {
            return source.refusal;
        }
        Resolved destination = resolve(to);
        if (destination.refusal != null) {
            return destination.refusal;
        }
        if (!source.repo.equals(destination.repo)) {
            return "Both paths have to be in the same repository.";
        }
        if (!source.file.exists()) {
            return "There is nothing at \"" + from + "\".";
        }
        if (destination.file.exists()) {
            return "There is already something at \"" + to + "\".";
        }
        File parent = destination.file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return "Could not make the folder for " + to + ".";
        }
        return source.file.renameTo(destination.file) ? "" : "Could not move " + from + ".";
    }

    // --- containment ----------------------------------------------------------

    /** A path resolved against the workspace, or the reason it was refused. */
    private static final class Resolved {
        final File file;
        final String repo;
        final String inner;
        final String refusal;

        private Resolved(File file, String repo, String inner, String refusal) {
            this.file = file;
            this.repo = repo;
            this.inner = inner;
            this.refusal = refusal;
        }

        static Resolved no(String refusal) {
            return new Resolved(null, "", "", refusal);
        }
    }

    /**
     * Turn {@code repo/inner/path} into a real file inside that repository.
     *
     * <p>The first segment names the clone; everything after it is the path
     * within it. Containment is checked on the resolved canonical path rather
     * than on the text, because "a/../../etc" is only obviously an escape once
     * the filesystem has had its say — and a symlink inside a cloned
     * repository can point anywhere at all.
     */
    private Resolved resolve(String path) {
        if (path == null || path.trim().isEmpty()) {
            return Resolved.no("Give me a path as repository/file.");
        }
        String cleaned = path.trim().replace('\\', '/');
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        int slash = cleaned.indexOf('/');
        String name = slash < 0 ? cleaned : cleaned.substring(0, slash);
        String inner = slash < 0 ? "" : cleaned.substring(slash + 1);
        File dir = repo(name);
        if (dir == null) {
            return Resolved.no(noRepo(name));
        }
        // The repository's own plumbing is not editable content. Rewriting a
        // hook or config through a file tool is a way to run code later.
        String lower = inner.toLowerCase(Locale.US);
        if (lower.equals(".git") || lower.startsWith(".git/")) {
            return Resolved.no("That is the repository's own git data, which is not editable.");
        }
        try {
            File base = dir.getCanonicalFile();
            File target = inner.isEmpty() ? base : new File(base, inner).getCanonicalFile();
            String basePath = base.getPath();
            String targetPath = target.getPath();
            if (!targetPath.equals(basePath) && !targetPath.startsWith(basePath + File.separator)) {
                return Resolved.no("Paths have to stay inside the repository.");
            }
            return new Resolved(target, name, inner, null);
        } catch (Exception error) {
            return Resolved.no("That path could not be resolved.");
        }
    }

    /** Depth-first removal: File.delete refuses a folder that is not empty. */
    private static boolean removeTree(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!removeTree(child)) {
                        return false;
                    }
                }
            }
        }
        return file.delete();
    }

    private static byte[] readAll(File file) throws java.io.IOException {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            closeQuietly(input);
        }
    }

    /**
     * A NUL byte in the first few KB means this is not text. Handing a model a
     * megabyte of mangled binary wastes its context and tells it nothing.
     */
    private static boolean looksBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8000);
        for (int index = 0; index < limit; index++) {
            if (bytes[index] == 0) {
                return true;
            }
        }
        return false;
    }

    private static void closeQuietly(java.io.Closeable stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Exception ignored) {
            // Nothing useful to do about a stream that will not close.
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
