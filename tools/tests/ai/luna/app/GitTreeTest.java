package ai.luna.app;

import org.eclipse.jgit.api.Git;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The git working-tree tools, run against real repositories on a real disk.
 *
 * <p>Nothing is mocked here. A repository is created with JGit, files are
 * written and read back through {@link AppGitStore}, and the assertions look at
 * what actually landed on the filesystem. The tools that matter most are the
 * ones that must <em>refuse</em>: a path that climbs out of the repository with
 * {@code ..} would let a model read anything on the phone, and a write into
 * {@code .git} would let it install a hook that runs later.
 */
public final class GitTreeTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"),
            "luna-git-tree-" + System.nanoTime());
        if (!root.mkdirs()) {
            throw new IllegalStateException("could not make a workspace");
        }
        try {
            File repo = new File(root, "demo");
            Git.init().setDirectory(repo).call().close();
            write(new File(repo, "README.md"), "# Demo\n\nA repository.\n");
            File src = new File(repo, "src");
            src.mkdirs();
            write(new File(src, "main.js"), "function main() {\n  return 1;\n}\n");

            AppGitStore store = new AppGitStore(root, null);

            listing(store);
            reading(store);
            writing(store);
            creating(store);
            moving(store);
            deleting(store);
            containment(store, root);
            binaries(store, repo);
        } finally {
            remove(root);
        }

        System.out.println();
        if (failed > 0) {
            System.out.println(failed + " FAILED, " + passed + " passed");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static void listing(AppGitStore store) {
        String top = store.list("demo");
        check("a repository lists its files", top.contains("README.md") && top.contains("src"));
        check("the .git folder is not offered", !top.contains(".git\n"));
        check("a folder inside lists too", store.list("demo/src").contains("main.js"));
        check("an unknown repository says so",
            store.list("nope").contains("no repository named"));
        check("a file is not a folder",
            store.list("demo/README.md").contains("is a file"));
    }

    private static void reading(AppGitStore store) {
        check("a file reads back", store.read("demo/README.md").contains("A repository."));
        check("a nested file reads back", store.read("demo/src/main.js").contains("return 1;"));
        check("a missing file says so",
            store.read("demo/nope.txt").contains("There is no file"));
        check("a folder is not a file", store.read("demo/src").contains("is a folder"));
    }

    private static void writing(AppGitStore store) {
        check("a write reports success", store.write("demo/src/main.js",
            "function main() {\n  return 2;\n}\n").isEmpty());
        check("and the new text is there",
            store.read("demo/src/main.js").contains("return 2;"));

        check("a write into a new folder works",
            store.write("demo/deep/nested/file.txt", "hello").isEmpty());
        check("and it reads back", store.read("demo/deep/nested/file.txt").equals("hello"));
    }

    private static void creating(AppGitStore store) {
        check("a new file is created", store.create("demo/fresh.txt", false).isEmpty());
        check("and it is empty", store.read("demo/fresh.txt").isEmpty());
        check("creating it twice is refused",
            store.create("demo/fresh.txt", false).contains("already something"));
        check("a folder is created", store.create("demo/newdir", true).isEmpty());
        check("and it lists as empty", store.list("demo/newdir").contains("is empty"));
    }

    private static void moving(AppGitStore store) {
        check("a file moves", store.move("demo/fresh.txt", "demo/moved.txt").isEmpty());
        check("the old name is gone",
            store.read("demo/fresh.txt").contains("There is no file"));
        check("the new name is there", store.read("demo/moved.txt").isEmpty());
        check("moving onto something is refused",
            store.move("demo/moved.txt", "demo/README.md").contains("already something"));
        check("moving between repositories is refused",
            store.move("demo/moved.txt", "other/x.txt").contains("no repository named"));
    }

    private static void deleting(AppGitStore store) {
        check("a file is deleted", store.delete("demo/moved.txt").isEmpty());
        check("and it is gone", store.read("demo/moved.txt").contains("There is no file"));
        check("a folder and its contents go",
            store.delete("demo/deep").isEmpty());
        check("and the folder is gone", store.list("demo/deep").contains("nothing at"));
        check("deleting the repository itself is refused",
            store.delete("demo").contains("repository itself"));
        check("deleting nothing says so",
            store.delete("demo/ghost.txt").contains("nothing at"));
    }

    // --- the ones that actually matter ---------------------------------------

    private static void containment(AppGitStore store, File root) throws Exception {
        // A file outside every repository, which must stay unreachable.
        write(new File(root, "secret.txt"), "not yours");

        check("a path cannot climb out with ..",
            store.read("demo/../secret.txt").contains("stay inside"));
        check("nor with a longer climb",
            store.read("demo/src/../../secret.txt").contains("stay inside"));
        check("nor can a write escape",
            store.write("demo/../escaped.txt", "x").contains("stay inside"));
        check("and nothing was written outside",
            !new File(root, "escaped.txt").exists());
        check("nor can a delete escape",
            store.delete("demo/../secret.txt").contains("stay inside"));
        check("and the outside file survives", new File(root, "secret.txt").exists());
        check("a move cannot escape either",
            store.move("demo/README.md", "demo/../stolen.md").contains("stay inside"));

        check("git's own data is not readable",
            store.read("demo/.git/config").contains("not editable"));
        check("git's own data is not writable",
            store.write("demo/.git/hooks/pre-commit", "#!/bin/sh\\nevil\\n")
                .contains("not editable"));
        check("and the hook was never written",
            !new File(new File(root, "demo"), ".git/hooks/pre-commit").exists());

        check("an empty path is refused", store.read("").contains("Give me a path"));
    }

    private static void binaries(AppGitStore store, File repo) throws Exception {
        File binary = new File(repo, "logo.png");
        FileOutputStream out = new FileOutputStream(binary);
        out.write(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13, 0, 1 });
        out.close();
        check("a binary file is not read as text",
            store.read("demo/logo.png").contains("binary file"));
    }

    // --- helpers --------------------------------------------------------------

    private static void write(File file, String content) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    private static void remove(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                remove(child);
            }
        }
        file.delete();
    }

    private static void check(String what, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  pass  " + what);
        } else {
            failed++;
            System.out.println("  FAIL  " + what);
        }
    }
}
