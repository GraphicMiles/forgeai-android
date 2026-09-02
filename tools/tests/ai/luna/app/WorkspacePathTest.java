package ai.luna.app;

import androidx.documentfile.provider.DocumentFile;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * Which file a path actually means.
 *
 * <p>This is where the reported bug lived: "put it in the alarms folder", said
 * by someone already standing in Alarms, produced Alarms/Alarms. The rule that
 * fixed it was only ever exercised on a device, because a granted folder needs
 * a real SAF grant -- so the fix went out untested and the remaining holes went
 * unnoticed. The store now accepts an in-memory tree, and these run the real
 * resolution logic against it.
 *
 * <p>The two readings that must both keep working are in tension: a repeated
 * folder name is usually the model saying the root's name once too often, but
 * occasionally the folder genuinely contains a child of the same name, and then
 * the literal reading is right. What decides between them is what exists.
 */
public final class WorkspacePathTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        repeatedRootName();
        genuineNestedFolder();
        theRootItself();
        ordinaryPaths();
        traversal();

        System.out.println();
        if (failed > 0) {
            System.out.println(failed + " FAILED, " + passed + " passed");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    // --- the reported bug -----------------------------------------------------

    private static void repeatedRootName() {
        // Granted folder "Alarms", holding life.js and a Music subfolder.
        Tree root = Tree.folder("Alarms",
            Tree.file("life.js"),
            Tree.folder("Music", Tree.file("a.mp3")));
        WorkspaceStore store = storeOn(root);

        check("a bare name is left alone", resolves(store, "life.js", "life.js"));
        check("the root's own name is dropped",
            resolves(store, "Alarms/life.js", "life.js"));
        check("case does not matter when dropping it",
            resolves(store, "alarms/life.js", "life.js"));
        check("shouting does not matter either",
            resolves(store, "ALARMS/life.js", "life.js"));

        // This is the one that was still broken: the fix only ever stripped one
        // leading copy, so the second Alarms survived and the folder was made.
        check("the name said twice is still dropped",
            resolves(store, "Alarms/Alarms/life.js", "life.js"));
        check("and three times",
            resolves(store, "Alarms/Alarms/Alarms/life.js", "life.js"));

        check("a real subfolder is kept",
            resolves(store, "Alarms/Music/a.mp3", "Music/a.mp3"));
        check("and is unaffected without the prefix",
            resolves(store, "Music/a.mp3", "Music/a.mp3"));

        // A file that does not exist yet is the create case, and must land in
        // the granted folder rather than in a new one named after it.
        check("a new file goes in the granted folder",
            resolves(store, "Alarms/fresh.txt", "fresh.txt"));
        check("even when the name is repeated",
            resolves(store, "Alarms/Alarms/fresh.txt", "fresh.txt"));
        check("a new file in a new subfolder keeps the subfolder",
            resolves(store, "Alarms/notes/fresh.txt", "notes/fresh.txt"));
    }

    private static void genuineNestedFolder() {
        // The awkward case: Alarms really does contain an Alarms.
        Tree root = Tree.folder("Alarms",
            Tree.folder("Alarms", Tree.file("inner.txt")),
            Tree.file("life.js"));
        WorkspaceStore store = storeOn(root);

        check("a real nested folder is reached literally",
            resolves(store, "Alarms/inner.txt", "Alarms/inner.txt"));
        check("and saying it twice still reaches it",
            resolves(store, "Alarms/Alarms/inner.txt", "Alarms/inner.txt"));
        check("while a file only in the root is still found",
            resolves(store, "Alarms/life.js", "life.js"));
    }

    // --- the granted folder is not Luna's to destroy --------------------------

    private static void theRootItself() {
        Tree root = Tree.folder("Alarms", Tree.file("life.js"));
        WorkspaceStore store = storeOn(root);

        check("the folder's own name resolves to the folder",
            resolves(store, "Alarms", ""));
        check("with a trailing slash too", resolves(store, "Alarms/", ""));

        // Deleting that would take the whole workspace, and there is no undo.
        check("deleting the granted folder is refused",
            refuses(store, "delete", "Alarms"));
        check("deleting an empty path is refused",
            refuses(store, "delete", ""));
        check("renaming the granted folder is refused",
            refuses(store, "rename", "Alarms"));

        // But a real file inside it is still deletable.
        check("a file inside is still deletable", !refuses(store, "delete", "life.js"));
    }

    private static void ordinaryPaths() {
        Tree root = Tree.folder("Docs",
            Tree.folder("src", Tree.file("main.js")),
            Tree.file("README.md"));
        WorkspaceStore store = storeOn(root);

        check("a nested path is untouched", resolves(store, "src/main.js", "src/main.js"));
        check("a leading slash is ignored", resolves(store, "/README.md", "README.md"));
        check("a stray dot is ignored", resolves(store, "./README.md", "README.md"));
        check("a doubled slash is ignored", resolves(store, "src//main.js", "src/main.js"));
        check("surrounding space is ignored",
            resolves(store, "  README.md  ", "README.md"));
        check("a folder that merely starts with the root's name is kept",
            resolves(store, "Docstore/x.txt", "Docstore/x.txt"));
    }

    private static void traversal() {
        Tree root = Tree.folder("Docs", Tree.file("README.md"));
        WorkspaceStore store = storeOn(root);
        check("climbing out is refused", refuses(store, "read", "../secret.txt"));
        check("climbing out mid-path is refused",
            refuses(store, "read", "src/../../secret.txt"));
        check("and it is refused for writes too",
            refuses(store, "delete", "../secret.txt"));
    }

    // --- helpers --------------------------------------------------------------

    private static WorkspaceStore storeOn(Tree root) {
        return WorkspaceStore.forPathTests(root);
    }

    /** Whether a path names the expected place, judged by what resolve() reaches. */
    private static boolean resolves(WorkspaceStore store, String path, String expected) {
        Tree reached = (Tree) store.resolveForTest(path);
        if (reached == null) {
            // Nothing exists there yet, so compare the segments the store chose.
            return expected.equals(String.join("/", store.segmentsForTest(path)));
        }
        return expected.equals(reached.pathFromRoot());
    }

    private static boolean refuses(WorkspaceStore store, String what, String path) {
        try {
            if (what.equals("delete")) {
                store.delete(path);
            } else if (what.equals("rename")) {
                store.rename(path, "whatever.txt");
            } else {
                store.readText(path);
            }
            return false;
        } catch (Exception expected) {
            return true;
        }
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

    /** A folder tree in memory, behaving the way a document provider does. */
    static final class Tree extends DocumentFile {
        private final String name;
        private final boolean directory;
        private final List<Tree> children = new ArrayList<>();
        private Tree parent;

        private Tree(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }

        static Tree folder(String name, Tree... kids) {
            Tree made = new Tree(name, true);
            for (Tree kid : kids) {
                kid.parent = made;
                made.children.add(kid);
            }
            return made;
        }

        static Tree file(String name) {
            return new Tree(name, false);
        }

        /** Where this sits relative to the granted folder, which is "". */
        String pathFromRoot() {
            if (parent == null) {
                return "";
            }
            String above = parent.pathFromRoot();
            return above.isEmpty() ? name : above + "/" + name;
        }

        @Override
        public Uri getUri() {
            return null;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isDirectory() {
            return directory;
        }

        @Override
        public boolean isFile() {
            return !directory;
        }

        @Override
        public long length() {
            return 0L;
        }

        @Override
        public long lastModified() {
            return 0L;
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public boolean canRead() {
            return true;
        }

        @Override
        public boolean canWrite() {
            return true;
        }

        @Override
        public boolean delete() {
            if (parent != null) {
                parent.children.remove(this);
            }
            return true;
        }

        @Override
        public DocumentFile[] listFiles() {
            return children.toArray(new DocumentFile[0]);
        }

        @Override
        public DocumentFile findFile(String wanted) {
            for (Tree child : children) {
                if (child.name.equals(wanted)) {
                    return child;
                }
            }
            return null;
        }

        @Override
        public DocumentFile createFile(String mime, String named) {
            Tree made = file(named);
            made.parent = this;
            children.add(made);
            return made;
        }

        @Override
        public DocumentFile createDirectory(String named) {
            Tree made = folder(named);
            made.parent = this;
            children.add(made);
            return made;
        }

        @Override
        public boolean renameTo(String named) {
            return false;
        }
    }
}
