package ai.luna.app;

import android.content.Intent;
import android.net.Uri;
import androidx.activity.result.ActivityResult;
import androidx.documentfile.provider.DocumentFile;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.json.JSONObject;

@CapacitorPlugin(name = "WorkspaceStorage")
public class WorkspaceStorage extends Plugin {
    private static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_BACKUPS = 20;
    private static final long MAX_BACKUP_BYTES = 40L * 1024L * 1024L;
    private static final Pattern BACKUP_ID = Pattern.compile("^[A-Za-z0-9-]{8,80}$");
    private static final Pattern SHA256 = Pattern.compile("^[a-fA-F0-9]{64}$");
    private static final String[] TEXT_EXTENSIONS = {
        ".txt", ".md", ".json", ".js", ".jsx", ".ts", ".tsx", ".css", ".html", ".xml",
        ".java", ".kt", ".kts", ".cpp", ".c", ".h", ".hpp", ".py", ".rb", ".go", ".rs",
        ".sh", ".bash", ".zsh", ".gradle", ".properties", ".yml", ".yaml", ".toml", ".ini",
        ".sql", ".graphql", ".gitignore", ".dockerignore"
    };

    private Uri rootUri;
    private final ConcurrentHashMap<String, Thread> activeModelDownloads = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HttpURLConnection> modelConnections = new ConcurrentHashMap<>();
    private final java.util.Set<String> pausedModelDownloads = ConcurrentHashMap.newKeySet();

    private static final class PathParts {
        final String parent;
        final String name;
        PathParts(String parent, String name) { this.parent = parent; this.name = name; }
    }

    @PluginMethod
    public void pickModelFile(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, intent, "modelFilePickerResult");
    }

    @ActivityCallback
    public void modelFilePickerResult(PluginCall call, ActivityResult result) {
        Intent data = result.getData();
        if (data == null || data.getData() == null) { call.reject("No model file was selected."); return; }
        Uri uri = data.getData();
        try {
            getContext().getContentResolver().takePersistableUriPermission(
                uri,
                data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (Exception ignored) {}
        DocumentFile doc = DocumentFile.fromSingleUri(getContext(), uri);
        String name = doc == null || doc.getName() == null ? "model.gguf" : doc.getName();
        JSObject response = new JSObject();
        response.put("uri", uri.toString());
        response.put("name", name);
        call.resolve(response);
    }

    @PluginMethod
    public void pickFolder(PluginCall call) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(call, intent, "folderPickerResult");
    }

    @ActivityCallback
    public void folderPickerResult(PluginCall call, ActivityResult result) {
        Intent data = result.getData();
        if (data == null || data.getData() == null) { call.reject("No folder was selected."); return; }
        rootUri = data.getData();
        int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContext().getContentResolver().takePersistableUriPermission(rootUri, flags); } catch (Exception ignored) {}
        JSObject response = new JSObject();
        response.put("uri", rootUri.toString());
        call.resolve(response);
        notifyListeners("folderSelected", response);
    }

    private DocumentFile root(PluginCall call) {
        String value = call.getString("uri", rootUri == null ? "" : rootUri.toString());
        if (value.isEmpty()) throw new IllegalArgumentException("Choose a workspace folder first.");
        Uri uri = Uri.parse(value);
        if (!"content".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException("A content tree URI is required.");
        rootUri = uri;
        DocumentFile root = DocumentFile.fromTreeUri(getContext(), rootUri);
        if (root == null || !root.isDirectory()) throw new IllegalArgumentException("Workspace folder is unavailable.");
        return root;
    }

    private boolean blockedSegment(String segment) {
        String name = segment == null ? "" : segment.toLowerCase(Locale.ROOT);
        return name.equals(".git") || name.equals(".hg") || name.equals(".svn")
            || name.equals(".ssh") || name.equals(".gnupg") || name.equals(".aws")
            || name.equals("node_modules") || name.equals(".env") || name.startsWith(".env.")
            || name.equals(".netrc") || name.equals(".npmrc") || name.equals(".pypirc")
            || name.equals("id_rsa") || name.equals("id_ed25519")
            || name.equals("credentials") || name.startsWith("credentials.") || name.startsWith("secret")
            || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12")
            || name.endsWith(".pfx") || name.endsWith(".jks") || name.endsWith(".keystore")
            || name.contains(".luna-tmp-") || name.contains(".luna-old-") || name.contains(".luna-trash-");
    }

    private String normalizeRelative(String relative, boolean allowRoot) {
        if (relative == null) throw new IllegalArgumentException("Workspace path is required.");
        String value = relative.trim();
        if (value.isEmpty()) {
            if (allowRoot) return "";
            throw new IllegalArgumentException("Workspace root cannot be used for this operation.");
        }
        if (value.startsWith("/") || value.startsWith("\\") || value.contains("\\") || value.indexOf('\0') >= 0 || value.contains(":")) {
            throw new IllegalArgumentException("Workspace paths must be safe relative paths.");
        }
        String[] parts = value.split("/", -1);
        for (String part : parts) {
            String decoded = Uri.decode(part);
            if (part.isEmpty() || part.equals(".") || part.equals("..") || decoded.equals(".") || decoded.equals("..")
                || decoded.contains("/") || decoded.contains("\\") || blockedSegment(decoded)) {
                throw new IllegalArgumentException("Unsafe or protected workspace path.");
            }
        }
        return String.join("/", parts);
    }

    private String normalizeName(String name) {
        String safe = normalizeRelative(name, false);
        if (safe.contains("/")) throw new IllegalArgumentException("Workspace item name must not contain a path separator.");
        return safe;
    }

    private PathParts splitPath(String path) {
        String safe = normalizeRelative(path, false);
        int slash = safe.lastIndexOf('/');
        return new PathParts(slash < 0 ? "" : safe.substring(0, slash), slash < 0 ? safe : safe.substring(slash + 1));
    }

    private DocumentFile resolve(DocumentFile root, String relative, boolean createFolders) {
        String safe = normalizeRelative(relative, true);
        DocumentFile current = root;
        if (safe.isEmpty()) return current;
        String[] segments = safe.split("/");
        for (int index = 0; index < segments.length; index++) {
            String part = segments[index];
            DocumentFile next = current.findFile(part);
            if (next == null && createFolders) next = current.createDirectory(part);
            if (next == null) throw new IllegalArgumentException("Workspace path not found.");
            if ((index < segments.length - 1 || createFolders) && !next.isDirectory()) {
                throw new IllegalArgumentException("Workspace path contains a non-folder segment.");
            }
            current = next;
        }
        return current;
    }

    @PluginMethod
    public void list(PluginCall call) {
        try {
            String path = normalizeRelative(call.getString("path", ""), true);
            DocumentFile directory = resolve(root(call), path, false);
            if (!directory.isDirectory()) throw new IllegalArgumentException("Workspace path is not a folder.");
            JSObject result = new JSObject();
            result.put("children", listNode(directory, path, 0));
            call.resolve(result);
        } catch (Exception error) { call.reject(error.getMessage()); }
    }

    private boolean isTrashReferenced(String trashName) {
        File[] metadata = backupDirectory().listFiles((dir, name) -> name.endsWith(".json"));
        if (metadata == null) return false;
        for (File file : metadata) {
            try {
                JSONObject item = new JSONObject(readLocalText(file));
                if (rootUri.toString().equals(item.optString("rootUri")) && trashName.equals(item.optString("trashName"))) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private JSArray listNode(DocumentFile directory, String parent, int depth) {
        JSArray array = new JSArray();
        if (depth > 8) return array;
        for (DocumentFile file : directory.listFiles()) {
            String name = file.getName() == null ? "" : file.getName();
            if (name.contains(".luna-trash-")) {
                if (!isTrashReferenced(name)) file.delete();
                continue;
            }
            if (name.isEmpty() || blockedSegment(name) || name.endsWith(".part")) continue;
            String path = parent.isEmpty() ? name : parent + "/" + name;
            JSObject item = new JSObject();
            item.put("name", name);
            item.put("type", file.isDirectory() ? "folder" : "file");
            item.put("path", path);
            if (file.isDirectory()) item.put("children", listNode(file, path, depth + 1));
            array.put(item);
        }
        return array;
    }

    private String mimeTypeForName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".js") || lower.endsWith(".mjs") || lower.endsWith(".cjs") || lower.endsWith(".jsx")) return "text/javascript";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "application/typescript";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".xml")) return "application/xml";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".java")) return "text/x-java-source";
        if (lower.endsWith(".kt") || lower.endsWith(".kts")) return "text/x-kotlin";
        if (lower.endsWith(".py")) return "text/x-python";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "application/yaml";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/x-luna-text";
    }

    @PluginMethod
    public void createFile(PluginCall call) {
        try {
            PathParts path = splitPath(call.getString("path", ""));
            DocumentFile parent = resolve(root(call), path.parent, true);
            if (parent.findFile(path.name) != null) throw new IllegalArgumentException("Workspace item already exists.");
            DocumentFile file = parent.createFile(mimeTypeForName(path.name), path.name);
            if (file == null) throw new IllegalArgumentException("Unable to create file.");
            call.resolve();
        } catch (Exception error) { call.reject(error.getMessage()); }
    }

    @PluginMethod
    public void createFolder(PluginCall call) {
        try {
            PathParts path = splitPath(call.getString("path", ""));
            DocumentFile parent = resolve(root(call), path.parent, true);
            if (parent.findFile(path.name) != null) throw new IllegalArgumentException("Workspace item already exists.");
            DocumentFile folder = parent.createDirectory(path.name);
            if (folder == null) throw new IllegalArgumentException("Unable to create folder.");
            call.resolve();
        } catch (Exception error) { call.reject(error.getMessage()); }
    }

    private int requestedLimit(PluginCall call, String key) {
        int value = call.getInt(key, MAX_TEXT_BYTES);
        if (value <= 0 || value > MAX_TEXT_BYTES) throw new IllegalArgumentException("Invalid workspace byte limit.");
        return value;
    }

    @PluginMethod
    public void readFile(PluginCall call) {
        try {
            int maxBytes = requestedLimit(call, "maxBytes");
            DocumentFile file = resolve(root(call), call.getString("path", ""), false);
            if (!file.isFile()) throw new IllegalArgumentException("Only workspace files can be read.");
            if (file.length() > maxBytes) throw new IllegalArgumentException("Workspace file exceeds the read limit.");
            try (InputStream input = getContext().getContentResolver().openInputStream(file.getUri());
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalArgumentException("Workspace file is unavailable.");
                byte[] buffer = new byte[8192];
                int count;
                int total = 0;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > maxBytes) throw new IllegalArgumentException("Workspace file exceeds the read limit.");
                    bytes.write(buffer, 0, count);
                }
                byte[] data = bytes.toByteArray();
                if (isBinary(file, data, data.length)) throw new IllegalArgumentException("Binary workspace files cannot be read as text.");
                JSObject result = new JSObject();
                result.put("content", new String(data, StandardCharsets.UTF_8));
                call.resolve(result);
            }
        } catch (Exception error) { call.reject(error.getMessage()); }
    }

    private File backupDirectory() {
        File directory = new File(getContext().getFilesDir(), "workspace-backups");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalArgumentException("Unable to create workspace backup directory.");
        return directory;
    }

    private byte[] readLocalFile(File file, int maxBytes) throws Exception {
        if (!file.isFile() || file.length() > maxBytes) throw new IllegalArgumentException("Workspace backup is unavailable.");
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) throw new IllegalArgumentException("Workspace backup exceeds the restore limit.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private String readLocalText(File file) throws Exception {
        return new String(readLocalFile(file, MAX_TEXT_BYTES), StandardCharsets.UTF_8);
    }

    private byte[] readDocument(DocumentFile file, int maxBytes) throws Exception {
        try (InputStream input = getContext().getContentResolver().openInputStream(file.getUri());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IllegalArgumentException("Workspace file is unavailable.");
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > maxBytes) throw new IllegalArgumentException("Workspace file exceeds the operation limit.");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private String sha256(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder output = new StringBuilder();
        for (byte value : hash) output.append(String.format(Locale.ROOT, "%02x", value));
        return output.toString();
    }

    private void writeMetadata(File file, JSONObject metadata) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(metadata.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private JSONObject baseMetadata(String id, String operation, String path) throws Exception {
        JSONObject metadata = new JSONObject();
        metadata.put("id", id);
        metadata.put("operation", operation);
        metadata.put("rootUri", rootUri.toString());
        metadata.put("path", path);
        metadata.put("createdAt", System.currentTimeMillis());
        return metadata;
    }

    private String createWriteBackup(String path, byte[] originalData) throws Exception {
        String id = UUID.randomUUID().toString();
        File directory = backupDirectory();
        File dataFile = new File(directory, id + ".bak");
        try (FileOutputStream output = new FileOutputStream(dataFile)) {
            output.write(originalData);
            output.flush();
        }
        JSONObject metadata = baseMetadata(id, "write", path);
        metadata.put("sha256", sha256(originalData));
        writeMetadata(new File(directory, id + ".json"), metadata);
        pruneBackups();
        return id;
    }

    private String createOperationBackup(String operation, String path, String secondaryName, String secondaryValue) throws Exception {
        String id = UUID.randomUUID().toString();
        JSONObject metadata = baseMetadata(id, operation, path);
        metadata.put(secondaryName, secondaryValue);
        writeMetadata(new File(backupDirectory(), id + ".json"), metadata);
        pruneBackups();
        return id;
    }

    private void cleanupExpiredBackup(JSONObject metadata) {
        if (!"delete".equals(metadata.optString("operation"))) return;
        try {
            Uri uri = Uri.parse(metadata.getString("rootUri"));
            DocumentFile workspace = DocumentFile.fromTreeUri(getContext(), uri);
            if (workspace == null) return;
            PathParts parts = splitPath(metadata.getString("path"));
            DocumentFile parent = resolve(workspace, parts.parent, false);
            DocumentFile trash = parent.findFile(metadata.getString("trashName"));
            if (trash != null) trash.delete();
        } catch (Exception ignored) {}
    }

    private void pruneBackups() {
        File directory = backupDirectory();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long total = 0;
        for (int index = 0; index < files.length; index++) {
            String id = files[index].getName().replace(".json", "");
            File data = new File(directory, id + ".bak");
            total += data.isFile() ? data.length() : 0;
            if (index >= MAX_BACKUPS || total > MAX_BACKUP_BYTES) {
                try { cleanupExpiredBackup(new JSONObject(readLocalText(files[index]))); } catch (Exception ignored) {}
                files[index].delete();
                data.delete();
            }
        }
    }

    private String replaceFile(DocumentFile workspace, String rawPath, byte[] data, boolean keepBackup) throws Exception {
        String path = normalizeRelative(rawPath, false);
        PathParts parts = splitPath(path);
        DocumentFile parent = resolve(workspace, parts.parent, false);
        DocumentFile original = parent.findFile(parts.name);
        if (original == null || !original.isFile()) throw new IllegalArgumentException("Workspace file does not exist.");

        String transactionId = UUID.randomUUID().toString();
        String tempName = parts.name + ".luna-tmp-" + transactionId;
        String oldName = parts.name + ".luna-old-" + transactionId;
        DocumentFile temp = parent.createFile("application/octet-stream", tempName);
        if (temp == null) throw new IllegalArgumentException("Unable to create transaction file.");

        try {
            try (OutputStream output = getContext().getContentResolver().openOutputStream(temp.getUri(), "wt")) {
                if (output == null) throw new IllegalArgumentException("Unable to open transaction file.");
                output.write(data);
                output.flush();
            }
            byte[] verified = readDocument(temp, MAX_TEXT_BYTES);
            if (!sha256(data).equals(sha256(verified))) throw new IllegalArgumentException("Workspace transaction verification failed.");

            byte[] originalData = readDocument(original, MAX_TEXT_BYTES);
            String backupId = keepBackup ? createWriteBackup(path, originalData) : null;

            if (!original.renameTo(oldName)) throw new IllegalArgumentException("Unable to preserve the original workspace file.");
            if (!temp.renameTo(parts.name)) {
                boolean rolledBack = original.renameTo(parts.name);
                temp.delete();
                if (!rolledBack) throw new IllegalArgumentException("Replacement failed and automatic rollback failed; use the workspace backup.");
                throw new IllegalArgumentException("Replacement failed; the original file was restored.");
            }
            original.delete();
            return backupId;
        } catch (Exception error) {
            temp.delete();
            throw error;
        }
    }

    @PluginMethod
    public void writeFile(PluginCall call) {
        try {
            int maxBytes = requestedLimit(call, "maxBytes");
            byte[] data = call.getString("content", "").getBytes(StandardCharsets.UTF_8);
            if (data.length > maxBytes) throw new IllegalArgumentException("Workspace write exceeds the byte limit.");
            String path = normalizeRelative(call.getString("path", ""), false);
            String backupId = replaceFile(root(call), path, data, true);
            JSObject result = new JSObject();
            result.put("path", path);
            result.put("size", data.length);
            result.put("backupId", backupId);
            result.put("operation", "write");
            call.resolve(result);
        } catch (Exception error) { call.reject(error.getMessage()); }
    }

    @PluginMethod
    public void listBackups(PluginCall call) {
        try {
            root(call);
            JSArray result = new JSArray();
            File[] files = backupDirectory().listFiles((dir, name) -> name.endsWith(".json"));
            if (files != null) {
                Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
                for (File file : files) {
                    try {
                        JSONObject metadata = new JSONObject(readLocalText(file));
                        if (!rootUri.toString().equals(metadata.optString("rootUri"))) continue;
                        JSObject item = new JSObject();
                        item.put("id", metadata.getString("id"));
                        item.put("path", metadata.getString("path"));
                        item.put("operation", metadata.optString("operation", "write"));
                        item.put("createdAt", metadata.getLong("createdAt"));
                        item.put("sha256", metadata.optString("sha256"));
                        result.put(item);
                    } catch (Exception ignored) {}
                }
            }
            JSObject response = new JSObject();
            response.put("backups", result);
            call.resolve(response);
        } catch (Exception error) { call.reject(error.getMessage()); }
    }

    @PluginMethod
    public void restoreBackup(PluginCall call) {
        try {
            DocumentFile workspace = root(call);
            String id = call.getString("backupId", "");
            if (!BACKUP_ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid workspace backup id.");
            File directory = backupDirectory();
            File metadataFile = new File(directory, id + ".json");
            File dataFile = new File(directory, id + ".bak");
            JSONObject metadata = new JSONObject(readLocalText(metadataFile));
            if (!rootUri.toString().equals(metadata.optString("rootUri"))) throw new IllegalArgumentException("Workspace backup belongs to another root.");
            String operation = metadata.optString("operation", "write");
            String path = normalizeRelative(metadata.getString("path"), false);
            PathParts parts = splitPath(path);
            DocumentFile parent = resolve(workspace, parts.parent, false);

            if ("write".equals(operation)) {
                byte[] data = readLocalFile(dataFile, MAX_TEXT_BYTES);
                if (!metadata.optString("sha256").equals(sha256(data))) throw new IllegalArgumentException("Workspace backup verification failed.");
                replaceFile(workspace, path, data, false);
            } else if ("delete".equals(operation)) {
                if (parent.findFile(parts.name) != null) throw new IllegalArgumentException("The original path is already occupied.");
                DocumentFile trash = parent.findFile(metadata.getString("trashName"));
                if (trash == null || !trash.renameTo(parts.name)) throw new IllegalArgumentException("Unable to restore deleted workspace item.");
            } else if ("rename".equals(operation)) {
                String newPath = normalizeRelative(metadata.getString("newPath"), false);
                PathParts newParts = splitPath(newPath);
                DocumentFile newParent = resolve(workspace, newParts.parent, false);
                if (!newParts.parent.equals(parts.parent)) throw new IllegalArgumentException("Cross-folder rename restore is not supported.");
                if (parent.findFile(parts.name) != null) throw new IllegalArgumentException("The original path is already occupied.");
                DocumentFile renamed = newParent.findFile(newParts.name);
                if (renamed == null || !renamed.renameTo(parts.name)) throw new IllegalArgumentException("Unable to undo workspace rename.");
            } else {
                throw new IllegalArgumentException("Unsupported workspace backup operation.");
            }

            metadataFile.delete();
            dataFile.delete();
            JSObject response = new JSObject();
            response.put("path", path);
            response.put("operation", operation);
            response.put("restored", true);
            call.resolve(response);
        } catch (Exception error) { call.reject(error.getMessage()); }
    }

    @PluginMethod
    public void rename(PluginCall call) {
        try {
            String path = normalizeRelative(call.getString("path", ""), false);
            String name = normalizeName(call.getString("newName", ""));
            PathParts parts = splitPath(path);
            String destination = parts.parent.isEmpty() ? name : parts.parent + "/" + name;
            normalizeRelative(destination, false);
            DocumentFile parent = resolve(root(call), parts.parent, false);
            if (parent.findFile(name) != null) throw new IllegalArgumentException("Workspace item already exists.");
            DocumentFile file = parent.findFile(parts.name);
            if (file == null || !file.renameTo(name)) throw new IllegalArgumentException("Unable to rename item.");
            String backupId;
            try { backupId = createOperationBackup("rename", path, "newPath", destination); }
            catch (Exception error) {
                file.renameTo(parts.name);
                throw error;
            }
            JSObject result = new JSObject();
            result.put("path", path); result.put("newPath", destination); result.put("backupId", backupId); result.put("operation", "rename");
            call.resolve(result);
        } catch (Exception error) { call.reject(error.getMessage()); }
    }

    @PluginMethod
    public void delete(PluginCall call) {
        try {
            String path = normalizeRelative(call.getString("path", ""), false);
            PathParts parts = splitPath(path);
            DocumentFile parent = resolve(root(call), parts.parent, false);
            DocumentFile file = parent.findFile(parts.name);
            if (file == null) throw new IllegalArgumentException("Workspace item not found.");
            if (!call.getBoolean("recoverable", true)) {
                if (!file.delete()) throw new IllegalArgumentException("Unable to permanently delete item.");
                JSObject permanent = new JSObject();
                permanent.put("path", path); permanent.put("operation", "permanent-delete");
                call.resolve(permanent);
                return;
            }
            String trashName = parts.name + ".luna-trash-" + UUID.randomUUID();
            if (!file.renameTo(trashName)) throw new IllegalArgumentException("Unable to move item into recoverable trash.");
            String backupId;
            try { backupId = createOperationBackup("delete", path, "trashName", trashName); }
            catch (Exception error) {
                file.renameTo(parts.name);
                throw error;
            }
            JSObject result = new JSObject();
            result.put("path", path); result.put("backupId", backupId); result.put("operation", "delete");
            call.resolve(result);
        } catch (Exception error) { call.reject(error.getMessage()); }
    }

    private boolean isTextName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String extension : TEXT_EXTENSIONS) if (lower.endsWith(extension)) return true;
        return false;
    }

    private boolean isBinary(DocumentFile file, byte[] sample, int count) {
        for (int index = 0; index < count; index++) if (sample[index] == 0) return true;
        String mime = file.getType() == null ? "" : file.getType().toLowerCase(Locale.ROOT);
        if (mime.startsWith("text/") || mime.contains("json") || mime.contains("javascript") || mime.contains("xml")
            || mime.contains("yaml") || mime.contains("toml") || isTextName(file.getName())) return false;
        int controls = 0;
        for (int index = 0; index < count; index++) {
            int value = sample[index] & 0xff;
            if (value < 32 && value != '\n' && value != '\r' && value != '\t' && value != '\f') controls++;
        }
        return count > 0 && controls > Math.max(2, count / 20);
    }

    @PluginMethod
    public void inspect(PluginCall call) {
        try {
            DocumentFile file = resolve(root(call), call.getString("path", ""), false);
            if (!file.isFile()) throw new IllegalArgumentException("Only workspace files can be inspected.");
            byte[] sample = new byte[4096];
            int count;
            try (InputStream input = getContext().getContentResolver().openInputStream(file.getUri())) {
                if (input == null) throw new IllegalArgumentException("Workspace file is unavailable.");
                count = input.read(sample);
            }
            JSObject result = new JSObject();
            result.put("type", "file");
            result.put("binary", isBinary(file, sample, Math.max(count, 0)));
            result.put("mimeType", file.getType() == null ? "" : file.getType());
            result.put("size", file.length());
            call.resolve(result);
        } catch (Exception error) { call.reject(error.getMessage()); }
    }

    private String sha256Document(DocumentFile file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = getContext().getContentResolver().openInputStream(file.getUri())) {
            if (input == null) throw new IllegalArgumentException("Model file is unavailable for checksum verification.");
            byte[] buffer = new byte[262144];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value));
        return hash.toString();
    }

    private boolean hasGgufHeader(DocumentFile file) throws Exception {
        try (InputStream input = getContext().getContentResolver().openInputStream(file.getUri())) {
            if (input == null) return false;
            byte[] magic = new byte[4];
            return input.read(magic) == 4 && magic[0] == 'G' && magic[1] == 'G' && magic[2] == 'U' && magic[3] == 'F';
        }
    }

    private String safeModelName(String raw) {
        String name = raw == null ? "model.gguf" : raw.trim();
        if (name.contains("/") || name.contains("\\") || name.equals(".") || name.equals("..") || name.startsWith(".")) {
            throw new IllegalArgumentException("Invalid model filename.");
        }
        name = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (name.length() > 128) name = name.substring(name.length() - 128);
        if (!name.toLowerCase(Locale.ROOT).endsWith(".gguf")) throw new IllegalArgumentException("Only GGUF models can be imported.");
        return name;
    }

    private JSObject importModelStream(InputStream input, String displayName) throws Exception {
        if (input == null) throw new IllegalArgumentException("Model source is unavailable.");
        String safeName = safeModelName(displayName);
        File directory = new File(getContext().getFilesDir(), "models");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalArgumentException("Unable to create runtime model directory.");
        String transaction = UUID.randomUUID().toString();
        File temp = new File(directory, transaction + ".part").getCanonicalFile();
        File canonicalDirectory = directory.getCanonicalFile();
        if (!temp.toPath().startsWith(canonicalDirectory.toPath())) throw new IllegalArgumentException("Unsafe runtime model path.");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream source = input; FileOutputStream output = new FileOutputStream(temp)) {
            byte[] buffer = new byte[262144];
            int count;
            while ((count = source.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                total += count;
            }
            output.flush();
        } catch (Exception error) {
            temp.delete();
            throw error;
        }
        if (total < 4096) { temp.delete(); throw new IllegalArgumentException("Model file is too small or incomplete."); }
        try (FileInputStream header = new FileInputStream(temp)) {
            byte[] magic = new byte[4];
            if (header.read(magic) != 4 || magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
                temp.delete();
                throw new IllegalArgumentException("Imported file does not have a GGUF header.");
            }
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value));
        File target = new File(directory, hash.substring(0, 16) + "-" + safeName).getCanonicalFile();
        if (!target.toPath().startsWith(canonicalDirectory.toPath())) { temp.delete(); throw new IllegalArgumentException("Unsafe runtime model path."); }
        if (target.exists()) temp.delete();
        else if (!temp.renameTo(target)) { temp.delete(); throw new IllegalArgumentException("Unable to finalize imported model."); }
        JSObject result = new JSObject();
        result.put("runtimePath", target.getAbsolutePath());
        result.put("sha256", hash.toString());
        result.put("size", target.length());
        result.put("name", safeName);
        return result;
    }

    @PluginMethod
    public void importDocumentToRuntime(PluginCall call) {
        new Thread(() -> {
            try {
                Uri uri = Uri.parse(call.getString("uri", ""));
                DocumentFile source = DocumentFile.fromSingleUri(getContext(), uri);
                String name = call.getString("name", source == null ? "model.gguf" : source.getName());
                call.resolve(importModelStream(getContext().getContentResolver().openInputStream(uri), name));
            } catch (Exception error) { call.reject(error.getMessage()); }
        }).start();
    }

    @PluginMethod
    public void importToRuntime(PluginCall call) {
        new Thread(() -> {
            try {
                DocumentFile source = resolve(root(call), call.getString("path", ""), false);
                if (!source.isFile()) throw new IllegalArgumentException("Model source is not a file.");
                call.resolve(importModelStream(
                    getContext().getContentResolver().openInputStream(source.getUri()),
                    source.getName()
                ));
            } catch (Exception error) { call.reject(error.getMessage()); }
        }).start();
    }

    @PluginMethod
    public void download(PluginCall call) {
        final String key;
        try { key = normalizeRelative(call.getString("path", ""), false); }
        catch (Exception error) { call.reject(error.getMessage()); return; }
        Thread worker = new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String url = call.getString("url", "");
                String path = key;
                String expectedSha = call.getString("sha256", "").toLowerCase(Locale.ROOT);
                if (!url.startsWith("https://") || !path.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
                    throw new IllegalArgumentException("Only HTTPS GGUF model downloads are allowed.");
                }
                if (!SHA256.matcher(expectedSha).matches()) throw new IllegalArgumentException("A trusted SHA-256 is required for catalog downloads.");
                DocumentFile folder = root(call);
                PathParts parts = splitPath(path);
                DocumentFile directory = resolve(folder, parts.parent, true);
                String tempName = parts.name + ".part";
                DocumentFile target = directory.findFile(tempName);
                long resumed = target == null ? 0 : target.length();
                if (target == null) target = directory.createFile("application/octet-stream", tempName);
                if (target == null) throw new IllegalArgumentException("Unable to create temporary model file.");

                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(20000);
                connection.setReadTimeout(120000);
                connection.setInstanceFollowRedirects(true);
                if (resumed > 0) connection.setRequestProperty("Range", "bytes=" + resumed + "-");
                connection.connect();
                int response = connection.getResponseCode();
                if (resumed > 0 && response != 206) {
                    target.delete();
                    resumed = 0;
                    connection.disconnect();
                    connection = (HttpURLConnection) new URL(url).openConnection();
                    connection.setConnectTimeout(20000);
                    connection.setReadTimeout(120000);
                    connection.connect();
                    response = connection.getResponseCode();
                    target = directory.createFile("application/octet-stream", tempName);
                    if (target == null) throw new IllegalArgumentException("Unable to recreate temporary model file.");
                }
                if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) throw new IllegalArgumentException("Model download redirected to a non-HTTPS URL.");
                if (response < 200 || response >= 300) throw new IllegalArgumentException("Model download failed: HTTP " + response);
                modelConnections.put(path, connection);
                long expected = connection.getContentLengthLong();
                if (expected > 0) expected += resumed;
                long total = resumed;
                try (InputStream input = connection.getInputStream();
                     OutputStream output = getContext().getContentResolver().openOutputStream(target.getUri(), resumed > 0 ? "wa" : "wt")) {
                    if (output == null) throw new IllegalArgumentException("Unable to open temporary model file.");
                    byte[] buffer = new byte[262144];
                    int count;
                    long last = 0;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted() || pausedModelDownloads.contains(path)) break;
                        output.write(buffer, 0, count);
                        total += count;
                        long now = System.currentTimeMillis();
                        if (now - last > 250) {
                            last = now;
                            JSObject progress = new JSObject();
                            progress.put("path", path);
                            progress.put("completed", total);
                            progress.put("total", expected);
                            progress.put("progress", expected > 0 ? (int) (total * 100 / expected) : 0);
                            notifyListeners("modelDownloadProgress", progress);
                        }
                    }
                    output.flush();
                }
                if (pausedModelDownloads.contains(path) || Thread.currentThread().isInterrupted()) {
                    JSObject result = new JSObject();
                    result.put("paused", pausedModelDownloads.contains(path));
                    result.put("cancelled", !pausedModelDownloads.contains(path));
                    result.put("path", path);
                    result.put("completed", total);
                    call.resolve(result);
                    return;
                }
                if (expected > 0 && total != expected) throw new IllegalArgumentException("Model download ended before the expected byte count.");
                if (!hasGgufHeader(target)) { target.delete(); throw new IllegalArgumentException("Downloaded file does not have a GGUF header."); }
                String actualSha = sha256Document(target);
                if (!expectedSha.equals(actualSha)) { target.delete(); throw new IllegalArgumentException("Downloaded model failed SHA-256 verification."); }
                DocumentFile existing = directory.findFile(parts.name);
                if (existing != null && !existing.renameTo(parts.name + ".luna-old-" + UUID.randomUUID())) {
                    throw new IllegalArgumentException("Unable to preserve the existing model file.");
                }
                if (!target.renameTo(parts.name)) {
                    if (existing != null) existing.renameTo(parts.name);
                    throw new IllegalArgumentException("Unable to finalize model download; the previous file was restored.");
                }
                if (existing != null) existing.delete();
                JSObject progress = new JSObject();
                progress.put("path", path);
                progress.put("completed", total);
                progress.put("total", total);
                progress.put("progress", 100);
                notifyListeners("modelDownloadProgress", progress);
                JSObject result = new JSObject();
                result.put("path", path);
                result.put("size", total);
                result.put("sha256", actualSha);
                result.put("verified", true);
                call.resolve(result);
            } catch (Exception error) {
                if (pausedModelDownloads.contains(key)) {
                    JSObject result = new JSObject();
                    result.put("paused", true);
                    result.put("path", key);
                    call.resolve(result);
                } else if (Thread.currentThread().isInterrupted()) {
                    JSObject result = new JSObject();
                    result.put("cancelled", true);
                    result.put("path", key);
                    call.resolve(result);
                } else {
                    call.reject(error.getMessage());
                }
            } finally {
                modelConnections.remove(key);
                activeModelDownloads.remove(key);
                if (connection != null) connection.disconnect();
            }
        });
        pausedModelDownloads.remove(key);
        if (activeModelDownloads.putIfAbsent(key, worker) != null) {
            call.reject("A download for this model is already active.");
            return;
        }
        worker.start();
    }

    @PluginMethod
    public void pauseDownload(PluginCall call) {
        String path = call.getString("path", "");
        pausedModelDownloads.add(path);
        HttpURLConnection connection = modelConnections.get(path);
        if (connection != null) connection.disconnect();
        Thread thread = activeModelDownloads.get(path);
        if (thread != null) thread.interrupt();
        call.resolve();
    }

    @PluginMethod
    public void cancelDownload(PluginCall call) {
        String path = call.getString("path", "");
        pausedModelDownloads.remove(path);
        HttpURLConnection connection = modelConnections.remove(path);
        if (connection != null) connection.disconnect();
        Thread thread = activeModelDownloads.remove(path);
        if (thread != null) thread.interrupt();
        try {
            DocumentFile folder = root(call);
            PathParts parts = splitPath(path);
            DocumentFile directory = resolve(folder, parts.parent, false);
            DocumentFile partial = directory.findFile(parts.name + ".part");
            if (partial != null) partial.delete();
        } catch (Exception ignored) {}
        call.resolve();
    }
}
