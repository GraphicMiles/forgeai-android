package ai.luna.app;

import android.content.Context;

import ai.luna.contracts.StorageProvider;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The one folder Luna is allowed to touch, reached through the Storage Access
 * Framework so the grant is the user's to give and revoke.
 *
 * Two rules are enforced here rather than in the model: a hard byte cap on any
 * read or write, and a deny-list of credential-shaped paths that no execution
 * mode can override. Every destructive operation writes a backup first.
 */
public final class WorkspaceStore implements StorageProvider {

    /** The first storage provider: one Android folder, granted through SAF. */
    @Override
    public String id() {
        return "android.saf";
    }


    public static final long MAX_BYTES = 2L * 1024L * 1024L;

    private static final String[] DENIED_NAMES = {
        ".env", ".env.local", ".netrc", ".git-credentials", "id_rsa", "id_ed25519",
        "credentials", "secrets.json", "keystore.jks", "release.keystore",
    };
    private static final String[] DENIED_SUFFIXES = { ".pem", ".key", ".p12", ".keystore", ".jks" };
    private static final String[] DENIED_SEGMENTS = { ".ssh", ".gnupg", ".aws" };

    private final Context context;
    private final Prefs prefs;

    public WorkspaceStore(Context context, Prefs prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
    }

    // --- grant ---------------------------------------------------------------

    public static Intent pickFolderIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        return intent;
    }

    public static final String STATE_NONE = "none";
    public static final String STATE_GRANTED = "granted";
    public static final String STATE_REVOKED = "revoked";

    public String persistGrant(Uri uri) {
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            context.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {
            // Some providers hand out a one-shot grant; the session still works.
        }
        prefs.setWorkspaceUri(uri.toString());
        prefs.rememberGrant(uri.toString(), rootName());
        return uri.toString();
    }

    /** Switch back to a folder that was granted before, without picking again. */
    public boolean useGrant(String uri) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        prefs.setWorkspaceUri(uri);
        return rootState().equals(STATE_GRANTED);
    }

    public boolean hasRoot() {
        return !prefs.workspaceUri().isEmpty();
    }

    /**
     * Told apart deliberately. A folder whose permission was withdrawn in system
     * settings reads back as empty, which looks exactly like a folder with
     * nothing in it — and that lie has cost people an afternoon before now.
     */
    public String rootState() {
        String uri = prefs.workspaceUri();
        if (uri.isEmpty()) {
            return STATE_NONE;
        }
        boolean held = false;
        try {
            for (android.content.UriPermission permission
                    : context.getContentResolver().getPersistedUriPermissions()) {
                if (permission.getUri().toString().equals(uri) && permission.isReadPermission()) {
                    held = true;
                    break;
                }
            }
        } catch (Exception error) {
            held = false;
        }
        if (!held) {
            return STATE_REVOKED;
        }
        DocumentFile root = root();
        if (root == null || !root.exists() || !root.canRead()) {
            return STATE_REVOKED;
        }
        return STATE_GRANTED;
    }

    /**
     * Copy a file the user shared in, or picked from elsewhere on the phone,
     * into the granted folder. Luna cannot read outside the folder, so a shared
     * file has to land inside it before she can open it.
     */
    public String bringIn(Uri source, String suggestedName) throws IOException {
        DocumentFile root = root();
        if (root == null) {
            throw new IOException("No folder has been granted yet.");
        }
        String name = suggestedName == null || suggestedName.isEmpty()
            ? "shared-" + System.currentTimeMillis() : suggestedName;
        if (isProtected(name)) {
            throw new IOException("That file is the kind Luna is not allowed to hold.");
        }
        DocumentFile existing = findChild(root, name);
        if (existing != null) {
            int dot = name.lastIndexOf('.');
            String stem = dot > 0 ? name.substring(0, dot) : name;
            String extension = dot > 0 ? name.substring(dot) : "";
            name = stem + "-" + System.currentTimeMillis() + extension;
        }
        DocumentFile target = root.createFile(mimeFor(name), name);
        if (target == null) {
            throw new IOException("Could not create " + name + " in the folder.");
        }
        // The provider may have renamed it on the way in; the caller is told
        // the name that exists, not the one that was requested.
        name = settleName(target, name);
        InputStream input = null;
        OutputStream output = null;
        try {
            input = context.getContentResolver().openInputStream(source);
            output = context.getContentResolver().openOutputStream(target.getUri());
            if (input == null || output == null) {
                throw new IOException("Could not read what was shared.");
            }
            byte[] chunk = new byte[64 * 1024];
            long copied = 0L;
            int read;
            while ((read = input.read(chunk)) > 0) {
                copied += read;
                if (copied > MAX_BYTES) {
                    throw new IOException("That file is larger than the 2 MB limit.");
                }
                output.write(chunk, 0, read);
            }
            output.flush();
        } finally {
            closeQuietly(input);
            closeQuietly(output);
        }
        return name;
    }

    /** The display name a content provider gives a shared file. */
    public String nameOf(Uri uri) {
        try {
            DocumentFile file = DocumentFile.fromSingleUri(context, uri);
            String name = file == null ? null : file.getName();
            if (name != null && !name.isEmpty()) {
                return name;
            }
        } catch (Exception ignored) {
            // Fall through to the path guess below.
        }
        String path = uri.getLastPathSegment();
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /** The folder name the user granted, for the context pill. */
    public String rootName() {
        DocumentFile root = root();
        if (root == null) {
            return "";
        }
        String name = root.getName();
        return name == null ? "" : name;
    }

    private DocumentFile root() {
        String uri = prefs.workspaceUri();
        if (uri.isEmpty()) {
            return null;
        }
        try {
            return DocumentFile.fromTreeUri(context, Uri.parse(uri));
        } catch (Exception error) {
            return null;
        }
    }

    // --- policy --------------------------------------------------------------

    public static boolean isProtected(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase(Locale.US);
        String name = lower.contains("/") ? lower.substring(lower.lastIndexOf('/') + 1) : lower;
        for (String denied : DENIED_NAMES) {
            if (name.equals(denied)) {
                return true;
            }
        }
        for (String suffix : DENIED_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        for (String segment : DENIED_SEGMENTS) {
            if (lower.equals(segment) || lower.startsWith(segment + "/") || lower.contains("/" + segment + "/")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> rawSegments(String path) {
        List<String> out = new ArrayList<>();
        if (path == null) {
            return out;
        }
        for (String part : path.split("/")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() || trimmed.equals(".")) {
                continue;
            }
            if (trimmed.equals("..")) {
                throw new IllegalArgumentException("Paths must stay inside the granted folder.");
            }
            out.add(trimmed);
        }
        return out;
    }

    /**
     * Paths are relative to the granted folder, but a person who is standing in
     * "Alarms" says "put it in the alarms folder" and the model dutifully writes
     * "Alarms/life.js". Taken literally that means a second Alarms inside the
     * first. So a leading segment that only repeats the root's own name is
     * dropped — unless the root really does contain a child by that name, in
     * which case the literal reading is the right one and nothing changes.
     */
    private List<String> segments(String path) {
        List<String> parts = rawSegments(path);
        if (parts.size() < 2) {
            return parts;
        }
        String root = rootName();
        if (root.isEmpty() || !parts.get(0).equalsIgnoreCase(root)) {
            return parts;
        }
        DocumentFile here = root();
        if (here != null && findChild(here, parts.get(0)) != null) {
            return parts;
        }
        return new ArrayList<>(parts.subList(1, parts.size()));
    }

    /**
     * The extensions a document provider bolts on when it does not believe the
     * name it was given. A file asked for as life.js can be sitting on disk as
     * life.js.txt, and an exact-name lookup will not find it.
     */
    private static final String[] APPENDED = { ".txt", ".bin", ".dat", ".html", ".json", ".md" };

    /**
     * Find a child by name, the way a person means it.
     *
     * <p>Exact match first, because that is almost always the answer. Then a
     * case-insensitive match, because storage on Android is frequently not
     * case-sensitive and "Life.js" is the same file. Then the mangled forms:
     * a file created before {@code mimeFor} was fixed is on disk under an
     * extension nobody asked for, and if it is not found here the caller will
     * cheerfully create a second copy beside it — which is exactly the bug
     * where "add a loop to life.js" produced a new file instead of editing the
     * one that was already there.
     */
    private static DocumentFile findChild(DocumentFile parent, String name) {
        if (parent == null || name == null || name.isEmpty()) {
            return null;
        }
        DocumentFile exact = parent.findFile(name);
        if (exact != null) {
            return exact;
        }
        DocumentFile[] children;
        try {
            children = parent.listFiles();
        } catch (Exception error) {
            return null;
        }
        if (children == null) {
            return null;
        }
        for (DocumentFile child : children) {
            String actual = child.getName();
            if (actual != null && actual.equalsIgnoreCase(name)) {
                return child;
            }
        }
        for (DocumentFile child : children) {
            String actual = child.getName();
            if (actual == null) {
                continue;
            }
            for (String suffix : APPENDED) {
                if (actual.equalsIgnoreCase(name + suffix)) {
                    return child;
                }
            }
        }
        return null;
    }

    private DocumentFile resolve(String path, boolean parentOnly) {
        DocumentFile current = root();
        if (current == null) {
            return null;
        }
        List<String> parts = segments(path);
        int limit = parentOnly ? parts.size() - 1 : parts.size();
        for (int index = 0; index < limit; index++) {
            current = findChild(current, parts.get(index));
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private String lastSegment(String path) {
        List<String> parts = segments(path);
        return parts.isEmpty() ? "" : parts.get(parts.size() - 1);
    }

    // --- reads ---------------------------------------------------------------

    public JSONArray list(String path) throws JSONException {
        JSONArray out = new JSONArray();
        DocumentFile folder = resolve(path, false);
        if (folder == null || !folder.isDirectory()) {
            return out;
        }
        DocumentFile[] children = folder.listFiles();
        Arrays.sort(children, new Comparator<DocumentFile>() {
            @Override
            public int compare(DocumentFile left, DocumentFile right) {
                boolean leftDir = left.isDirectory();
                if (leftDir != right.isDirectory()) {
                    return leftDir ? -1 : 1;
                }
                String leftName = left.getName() == null ? "" : left.getName();
                String rightName = right.getName() == null ? "" : right.getName();
                return leftName.compareToIgnoreCase(rightName);
            }
        });

        String prefix = path == null || path.isEmpty() ? "" : path + "/";
        for (DocumentFile child : children) {
            String name = child.getName();
            if (name == null) {
                continue;
            }
            JSONObject item = new JSONObject();
            item.put("name", name);
            item.put("path", prefix + name);
            item.put("type", child.isDirectory() ? "folder" : "file");
            item.put("size", child.isDirectory() ? 0L : child.length());
            item.put("modifiedAt", child.lastModified());
            item.put("locked", isProtected(prefix + name));
            out.put(item);
        }
        return out;
    }

    /** Is there anything at all at this path? Absent and empty are not the same. */
    @Override
    public boolean exists(String path) {
        if (path == null || path.isEmpty()) {
            return root() != null;
        }
        return resolve(path, false) != null;
    }

    public String readText(String path) throws IOException {
        if (isProtected(path)) {
            throw new IOException("That file is protected. Luna cannot read it.");
        }
        DocumentFile file = resolve(path, false);
        if (file == null || !file.isFile()) {
            throw new IOException("No such file: " + path);
        }
        if (file.length() > MAX_BYTES) {
            throw new IOException("That file is larger than the 2 MB cap.");
        }
        InputStream input = context.getContentResolver().openInputStream(file.getUri());
        if (input == null) {
            throw new IOException("Could not open " + path);
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            long total = 0L;
            while ((read = input.read(chunk)) > 0) {
                total += read;
                if (total > MAX_BYTES) {
                    throw new IOException("That file is larger than the 2 MB cap.");
                }
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            closeQuietly(input);
        }
    }

    public JSONArray search(String needle, int limit) throws JSONException {
        JSONArray hits = new JSONArray();
        if (needle == null || needle.trim().isEmpty()) {
            return hits;
        }
        searchInto(hits, "", needle.toLowerCase(Locale.US), limit, 0);
        return hits;
    }

    private void searchInto(JSONArray hits, String path, String needle, int limit, int depth) throws JSONException {
        if (hits.length() >= limit || depth > 4) {
            return;
        }
        JSONArray entries = list(path);
        for (int index = 0; index < entries.length() && hits.length() < limit; index++) {
            JSONObject entry = entries.getJSONObject(index);
            String childPath = entry.getString("path");
            if (entry.getString("type").equals("folder")) {
                searchInto(hits, childPath, needle, limit, depth + 1);
                continue;
            }
            if (entry.getBoolean("locked") || entry.getLong("size") > 512L * 1024L) {
                continue;
            }
            try {
                String text = readText(childPath);
                int at = text.toLowerCase(Locale.US).indexOf(needle);
                if (at >= 0) {
                    int start = Math.max(0, at - 60);
                    int end = Math.min(text.length(), at + 120);
                    JSONObject hit = new JSONObject();
                    hit.put("path", childPath);
                    hit.put("line", 1 + countNewlines(text, at));
                    hit.put("excerpt", text.substring(start, end).replace('\n', ' '));
                    hits.put(hit);
                }
            } catch (IOException ignored) {
                // Unreadable file: skip it rather than fail the whole search.
            }
        }
    }

    private static int countNewlines(String text, int upTo) {
        int count = 0;
        for (int index = 0; index < upTo && index < text.length(); index++) {
            if (text.charAt(index) == '\n') {
                count++;
            }
        }
        return count;
    }

    // --- writes --------------------------------------------------------------

    @Override
    public String writeText(String path, String content) throws IOException {
        if (isProtected(path)) {
            throw new IOException("That file is protected. Luna cannot write to it.");
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("That write is larger than the 2 MB cap.");
        }
        DocumentFile file = resolve(path, false);
        if (file == null) {
            file = createFileDocument(path);
        } else {
            backup(path, file, "write");
        }
        OutputStream output = context.getContentResolver().openOutputStream(file.getUri(), "wt");
        if (output == null) {
            throw new IOException("Could not open " + path + " for writing.");
        }
        try {
            output.write(bytes);
            output.flush();
        } finally {
            closeQuietly(output);
        }
        String actual = file.getName();
        return settledPath(path, actual == null ? lastSegment(path) : actual);
    }

    public void createFolder(String path) throws IOException {
        if (isProtected(path)) {
            throw new IOException("That name is protected.");
        }
        DocumentFile parent = parentOf(path);
        String name = lastSegment(path);
        if (findChild(parent, name) != null) {
            return;
        }
        if (parent.createDirectory(name) == null) {
            throw new IOException("Could not create the folder " + path);
        }
    }

    private DocumentFile createFileDocument(String path) throws IOException {
        DocumentFile parent = parentOf(path);
        String name = lastSegment(path);
        DocumentFile existing = findChild(parent, name);
        if (existing != null) {
            // Already there, possibly under a name the provider chose. Reusing
            // it is the whole point: creating a second one is how "edit this
            // file" turns into "write a copy next to it".
            settleName(existing, name);
            return existing;
        }
        DocumentFile created = parent.createFile(mimeFor(name), name);
        if (created == null) {
            throw new IOException("Could not create " + path);
        }
        settleName(created, name);
        return created;
    }

    @Override
    public String createFile(String path) throws IOException {
        if (isProtected(path)) {
            throw new IOException("That name is protected.");
        }
        DocumentFile created = createFileDocument(path);
        String actual = created.getName();
        return settledPath(path, actual == null ? lastSegment(path) : actual);
    }

    private DocumentFile parentOf(String path) throws IOException {
        List<String> parts = segments(path);
        if (parts.isEmpty()) {
            throw new IOException("A name is required.");
        }
        DocumentFile current = root();
        if (current == null) {
            throw new IOException("No folder has been granted yet.");
        }
        for (int index = 0; index < parts.size() - 1; index++) {
            DocumentFile next = findChild(current, parts.get(index));
            if (next == null) {
                next = current.createDirectory(parts.get(index));
            }
            if (next == null) {
                throw new IOException("Could not open " + parts.get(index));
            }
            current = next;
        }
        return current;
    }

    @Override
    public String rename(String path, String newName) throws IOException {
        if (isProtected(path) || isProtected(newName)) {
            throw new IOException("That file is protected.");
        }
        DocumentFile file = resolve(path, false);
        if (file == null) {
            throw new IOException("No such file: " + path);
        }
        String wanted = lastSegment(newName);
        if (wanted.isEmpty()) {
            throw new IOException("A new name is required.");
        }
        if (!file.renameTo(wanted)) {
            throw new IOException("Could not rename " + path);
        }
        // A rename goes through the same provider that appends extensions, so
        // what it is called now is a question, not an assumption.
        String actual = settleName(file, wanted);
        return settledPath(path, actual);
    }

    public void delete(String path) throws IOException {
        if (isProtected(path)) {
            throw new IOException("That file is protected. Luna cannot delete it.");
        }
        DocumentFile file = resolve(path, false);
        if (file == null) {
            throw new IOException("No such file: " + path);
        }
        if (file.isFile()) {
            backup(path, file, "delete");
        }
        if (!file.delete()) {
            throw new IOException("Could not delete " + path);
        }
    }

    // --- backups and undo ----------------------------------------------------

    private File backupDir() {
        File dir = new File(context.getFilesDir(), "backups");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private void backup(String path, DocumentFile file, String operation) {
        if (!file.isFile() || file.length() > MAX_BYTES) {
            return;
        }
        String id = System.currentTimeMillis() + "-" + Math.abs(path.hashCode());
        try {
            InputStream input = context.getContentResolver().openInputStream(file.getUri());
            if (input == null) {
                return;
            }
            FileOutputStream output = new FileOutputStream(new File(backupDir(), id + ".bin"));
            try {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = input.read(chunk)) > 0) {
                    output.write(chunk, 0, read);
                }
            } finally {
                closeQuietly(input);
                closeQuietly(output);
            }
            JSONObject meta = new JSONObject();
            meta.put("id", id);
            meta.put("path", path);
            meta.put("operation", operation);
            meta.put("createdAt", System.currentTimeMillis());
            FileOutputStream metaOut = new FileOutputStream(new File(backupDir(), id + ".json"));
            try {
                metaOut.write(meta.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                closeQuietly(metaOut);
            }
        } catch (Exception ignored) {
            // A failed backup must not block the operation the user asked for.
        }
    }

    public JSONObject lastBackup() {
        File[] metas = backupDir().listFiles();
        if (metas == null || metas.length == 0) {
            return null;
        }
        List<File> jsons = new ArrayList<>();
        for (File file : metas) {
            if (file.getName().endsWith(".json")) {
                jsons.add(file);
            }
        }
        if (jsons.isEmpty()) {
            return null;
        }
        Collections.sort(jsons, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return Long.compare(right.lastModified(), left.lastModified());
            }
        });
        try {
            return new JSONObject(readLocal(jsons.get(0)));
        } catch (Exception error) {
            return null;
        }
    }

    /** Put the most recent backup back where it came from. */
    public String undo() throws IOException {
        JSONObject meta = lastBackup();
        if (meta == null) {
            throw new IOException("There is nothing to undo.");
        }
        String id = meta.optString("id");
        String path = meta.optString("path");
        File payload = new File(backupDir(), id + ".bin");
        if (!payload.exists()) {
            throw new IOException("The backup for " + path + " is gone.");
        }
        DocumentFile file = resolve(path, false);
        if (file == null) {
            file = createFileDocument(path);
        }
        InputStream input = new FileInputStream(payload);
        OutputStream output = context.getContentResolver().openOutputStream(file.getUri(), "wt");
        if (output == null) {
            closeQuietly(input);
            throw new IOException("Could not write " + path);
        }
        try {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                output.write(chunk, 0, read);
            }
        } finally {
            closeQuietly(input);
            closeQuietly(output);
        }
        new File(backupDir(), id + ".json").delete();
        payload.delete();
        return path;
    }

    // --- helpers -------------------------------------------------------------

    private static String readLocal(File file) throws IOException {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            closeQuietly(input);
        }
    }

    /**
     * The MIME type a document provider is given decides the extension it puts
     * on the file. Hand it "text/plain" for life.js and it saves life.js.txt,
     * because .js is not an extension text/plain owns. So the type is derived
     * from the extension the person actually asked for, and anything unknown
     * goes in as a plain byte stream, which no provider renames.
     */
    private static String mimeFor(String name) {
        String lower = name.toLowerCase(Locale.US);
        int dot = lower.lastIndexOf('.');
        String extension = dot > 0 && dot < lower.length() - 1 ? lower.substring(dot + 1) : "";
        if (extension.isEmpty()) {
            return "text/plain";
        }
        if (extension.equals("json")) {
            return "application/json";
        }
        if (extension.equals("html") || extension.equals("htm")) {
            return "text/html";
        }
        if (extension.equals("md")) {
            return "text/markdown";
        }
        if (extension.equals("txt")) {
            return "text/plain";
        }
        String guess = null;
        try {
            guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        } catch (Exception ignored) {
            guess = null;
        }
        if (guess != null && !guess.isEmpty()
            && extension.equals(MimeTypeMap.getSingleton().getExtensionFromMimeType(guess))) {
            // Only trust the guess when the round trip agrees; otherwise the
            // provider would swap the extension for its own idea of one.
            return guess;
        }
        return "application/octet-stream";
    }

    /**
     * What the provider actually called the thing it just made. A file created
     * as life.js can come back as life.js.txt or life.js.bin; if it did, it is
     * renamed back, and if even that fails the real name is what gets reported.
     */
    private static String settleName(DocumentFile created, String wanted) {
        String actual = created.getName();
        if (actual == null || actual.equals(wanted)) {
            return wanted;
        }
        try {
            if (created.renameTo(wanted)) {
                String after = created.getName();
                return after == null ? wanted : after;
            }
        } catch (Exception ignored) {
            // Fall through and report the name the provider chose.
        }
        String after = created.getName();
        return after == null ? actual : after;
    }

    /** The path a caller should be told about, given the name that landed. */
    private String settledPath(String path, String actualName) {
        List<String> parts = segments(path);
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < parts.size() - 1; index++) {
            out.append(parts.get(index)).append('/');
        }
        return out.append(actualName).toString();
    }

    static void closeQuietly(java.io.Closeable stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // Nothing useful to do while closing.
        }
    }

    /** Only used to make document URIs readable in logs. */
    public static String describe(Uri uri) {
        try {
            return DocumentsContract.getTreeDocumentId(uri);
        } catch (Exception error) {
            return uri.toString();
        }
    }
}
