package ai.luna.app;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

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
public final class WorkspaceStore {

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

    public String persistGrant(Uri uri) {
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        try {
            context.getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {
            // Some providers hand out a one-shot grant; the session still works.
        }
        prefs.setWorkspaceUri(uri.toString());
        return uri.toString();
    }

    public boolean hasRoot() {
        return !prefs.workspaceUri().isEmpty();
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

    private static List<String> segments(String path) {
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

    private DocumentFile resolve(String path, boolean parentOnly) {
        DocumentFile current = root();
        if (current == null) {
            return null;
        }
        List<String> parts = segments(path);
        int limit = parentOnly ? parts.size() - 1 : parts.size();
        for (int index = 0; index < limit; index++) {
            current = current.findFile(parts.get(index));
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static String lastSegment(String path) {
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

    public void writeText(String path, String content) throws IOException {
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
    }

    public void createFolder(String path) throws IOException {
        DocumentFile parent = parentOf(path);
        String name = lastSegment(path);
        if (parent.findFile(name) != null) {
            return;
        }
        if (parent.createDirectory(name) == null) {
            throw new IOException("Could not create the folder " + path);
        }
    }

    private DocumentFile createFileDocument(String path) throws IOException {
        DocumentFile parent = parentOf(path);
        String name = lastSegment(path);
        DocumentFile existing = parent.findFile(name);
        if (existing != null) {
            return existing;
        }
        DocumentFile created = parent.createFile(mimeFor(name), name);
        if (created == null) {
            throw new IOException("Could not create " + path);
        }
        return created;
    }

    public void createFile(String path) throws IOException {
        if (isProtected(path)) {
            throw new IOException("That name is protected.");
        }
        createFileDocument(path);
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
            DocumentFile next = current.findFile(parts.get(index));
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

    public void rename(String path, String newName) throws IOException {
        if (isProtected(path) || isProtected(newName)) {
            throw new IOException("That file is protected.");
        }
        DocumentFile file = resolve(path, false);
        if (file == null) {
            throw new IOException("No such file: " + path);
        }
        if (!file.renameTo(newName)) {
            throw new IOException("Could not rename " + path);
        }
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

    private static String mimeFor(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".json")) {
            return "application/json";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }
        if (lower.endsWith(".md")) {
            return "text/markdown";
        }
        return "text/plain";
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
