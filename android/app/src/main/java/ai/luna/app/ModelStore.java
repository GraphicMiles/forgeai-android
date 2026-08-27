package ai.luna.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The model zoo: a fixed catalogue of checksummed GGUF builds, a resumable
 * downloader, and the files already on the phone.
 *
 * Every entry is pinned to an immutable revision URL and verified by SHA-256
 * after download. A file that fails the check is deleted, not kept.
 */
public final class ModelStore {

    /** One catalogue entry. */
    public static final class Entry {
        public final String id;
        public final String name;
        public final String params;
        public final String file;
        public final long sizeBytes;
        public final long minRamBytes;
        public final int contextTokens;
        public final int maxOutputTokens;
        public final String url;
        public final String sha256;
        public final String systemPrompt;

        Entry(String id, String name, String params, String file, long sizeBytes, int minRamGb,
              int contextTokens, int maxOutputTokens, String url, String sha256, String systemPrompt) {
            this.id = id;
            this.name = name;
            this.params = params;
            this.file = file;
            this.sizeBytes = sizeBytes;
            this.minRamBytes = (long) minRamGb * 1024L * 1024L * 1024L;
            this.contextTokens = contextTokens;
            this.maxOutputTokens = maxOutputTokens;
            this.url = url;
            this.sha256 = sha256;
            this.systemPrompt = systemPrompt;
        }

        JSONObject toJson(boolean installed, long onDisk) throws JSONException {
            JSONObject json = new JSONObject();
            json.put("id", id);
            json.put("name", name);
            json.put("params", params);
            json.put("file", file);
            json.put("sizeBytes", sizeBytes);
            json.put("minRamBytes", minRamBytes);
            json.put("contextTokens", contextTokens);
            json.put("installed", installed);
            json.put("onDiskBytes", onDisk);
            return json;
        }
    }

    public static final Entry[] CATALOG = {
        new Entry("smollm-135m-q3", "SmolLM 135M", "135M", "SmolLM-135M-Instruct.Q3_K_M.gguf",
            93_510_048L, 2, 2048, 128,
            "https://huggingface.co/QuantFactory/SmolLM-135M-Instruct-GGUF/resolve/d36054e030c66b4be24b0c65513ece348db06ba5/SmolLM-135M-Instruct.Q3_K_M.gguf?download=true",
            "8446b8924fe1c723254d60b5ef008fda7df9d8cea8bf143d07ea74c8efd4f1b5",
            "You are a helpful assistant."),
        new Entry("smollm2-360m-q3", "SmolLM2 360M", "360M", "SmolLM2-360M-Instruct-Q3_K_M.gguf",
            234_686_880L, 2, 4096, 384,
            "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/7be6f65f1db715fe5dc5a4634c0d459b4eed42ec/SmolLM2-360M-Instruct-Q3_K_M.gguf?download=true",
            "39683fe57014873905cf7fa25a5beecf36d355b900a0270eb049fd560c85cf63",
            "You are a helpful AI assistant named SmolLM, trained by Hugging Face"),
        new Entry("qwen2.5-0.5b-q4", "Qwen2.5 0.5B", "0.5B", "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            491_400_032L, 3, 4096, 512,
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true",
            "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
            "You are Qwen, created by Alibaba Cloud. You are a helpful assistant."),
        new Entry("qwen2.5-coder-0.5b-q4", "Qwen2.5-Coder 0.5B", "0.5B", "qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
            491_400_064L, 3, 4096, 384,
            "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/ebb2015119c907b064c512bf053e945850b5875f/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf?download=true",
            "1d9614638d18024d0fbb36575a15f1302a3adf044df10345688ec4f6e1c4ff32",
            "You are Qwen, created by Alibaba Cloud. You are a helpful coding assistant."),
        new Entry("smollm2-1.7b-q4", "SmolLM2 1.7B", "1.7B", "SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            1_055_609_824L, 4, 4096, 512,
            "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/1f03464768bfcc0319fc50da8ff5fb20b6417ba2/SmolLM2-1.7B-Instruct-Q4_K_M.gguf?download=true",
            "77665ea4815999596525c636fbeb56ba8b080b46ae85efef4f0d986a139834d7",
            "You are a helpful AI assistant named SmolLM, trained by Hugging Face"),
        new Entry("qwen2.5-1.5b-q4", "Qwen2.5 1.5B", "1.5B", "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            1_117_320_736L, 4, 4096, 512,
            "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true",
            "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
            "You are Qwen, created by Alibaba Cloud. You are a helpful assistant."),
        new Entry("qwen2.5-coder-1.5b-q4", "Qwen2.5-Coder 1.5B", "1.5B", "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            1_117_320_768L, 4, 4096, 512,
            "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/f86cb2c1fa58255f8052cc32aeede1b7482d4361/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf?download=true",
            "cc324af070c2ecbfd324a30884d2f951a7ff756aba85cb811a6ec436933bb046",
            "You are Qwen, created by Alibaba Cloud. You are a helpful coding assistant."),
        new Entry("qwen2.5-coder-3b-q3", "Qwen2.5-Coder 3B", "3B", "qwen2.5-coder-3b-instruct-q3_k_m.gguf",
            1_724_178_880L, 6, 4096, 640,
            "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/f74adce6aa16316c625447af059dbebe4983757c/qwen2.5-coder-3b-instruct-q3_k_m.gguf?download=true",
            "fc3937db7dda9d9ef68ce1f63b5a84ac850ec3c07578461d645e5a88509348e3",
            "You are Qwen, created by Alibaba Cloud. You are a helpful coding assistant."),
    };

    /** Reports bytes as they land, so the UI can draw a truthful bar. */
    public interface ProgressSink {
        void onProgress(String modelId, long completed, long total, String status);
    }

    private final Context context;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private volatile String downloadingId = "";

    public ModelStore(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * A model you brought yourself, dressed up as a catalogue entry so the rest
     * of the engine does not have to care where it came from. There is no
     * publisher and no checksum, so the size is whatever is on disk.
     */
    public static Entry imported(String id, String name, File file) {
        return new Entry(id, name, describeGguf(file), file.getName(), file.length(), 0,
            4096, 512, "", "", "You are a helpful assistant.");
    }

    public static Entry find(String id) {
        for (Entry entry : CATALOG) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    public File modelsDir() {
        File dir = new File(context.getFilesDir(), "models");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public File fileFor(Entry entry) {
        return new File(modelsDir(), entry.file);
    }

    public boolean isInstalled(Entry entry) {
        File file = fileFor(entry);
        return file.exists() && file.length() == entry.sizeBytes;
    }

    public JSONArray catalog() throws JSONException {
        JSONArray out = new JSONArray();
        for (Entry entry : CATALOG) {
            File file = fileFor(entry);
            out.put(entry.toJson(isInstalled(entry), file.exists() ? file.length() : 0L));
        }
        return out;
    }

    public List<Entry> installed() {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : CATALOG) {
            if (isInstalled(entry)) {
                out.add(entry);
            }
        }
        return out;
    }

    public boolean delete(String id) {
        Entry entry = find(id);
        if (entry == null) {
            return false;
        }
        File file = fileFor(entry);
        return !file.exists() || file.delete();
    }

    public void cancelDownload() {
        cancelled.set(true);
    }

    /** Stop, but keep the part file. The next attempt carries on from the byte. */
    public void pauseDownload() {
        paused.set(true);
    }

    /** How much of a model is already on disk, whether or not it is finished. */
    public long bytesOnDisk(Entry entry) {
        File target = fileFor(entry);
        if (target.exists()) {
            return target.length();
        }
        File partial = new File(target.getAbsolutePath() + ".part");
        return partial.exists() ? partial.length() : 0L;
    }

    /** Throw away a half-finished download for good. */
    public void discardPartial(Entry entry) {
        File partial = new File(fileFor(entry).getAbsolutePath() + ".part");
        if (partial.exists()) {
            partial.delete();
        }
    }

    public String downloadingId() {
        return downloadingId;
    }

    /**
     * Blocking, resumable download with a SHA-256 gate. Returns null on success
     * or a human-readable reason on failure.
     */
    public String download(Entry entry, ProgressSink sink) {
        File target = fileFor(entry);
        File partial = new File(target.getAbsolutePath() + ".part");
        cancelled.set(false);
        paused.set(false);
        downloadingId = entry.id;

        HttpURLConnection connection = null;
        InputStream input = null;
        FileOutputStream output = null;
        try {
            long already = partial.exists() ? partial.length() : 0L;
            if (already > entry.sizeBytes) {
                partial.delete();
                already = 0L;
            }

            connection = (HttpURLConnection) new URL(entry.url).openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(true);
            if (already > 0L) {
                connection.setRequestProperty("Range", "bytes=" + already + "-");
            }
            int code = connection.getResponseCode();
            boolean resuming = code == HttpURLConnection.HTTP_PARTIAL;
            if (code != HttpURLConnection.HTTP_OK && !resuming) {
                return "The download server answered " + code + ".";
            }
            if (!resuming) {
                already = 0L;
            }

            input = connection.getInputStream();
            output = new FileOutputStream(partial, resuming);
            byte[] chunk = new byte[64 * 1024];
            long completed = already;
            long lastReport = 0L;
            int read;
            sink.onProgress(entry.id, completed, entry.sizeBytes, "downloading");
            while ((read = input.read(chunk)) > 0) {
                if (cancelled.get()) {
                    output.flush();
                    WorkspaceStore.closeQuietly(output);
                    output = null;
                    partial.delete();
                    sink.onProgress(entry.id, 0L, entry.sizeBytes, "cancelled");
                    return "Cancelled.";
                }
                if (paused.get()) {
                    output.flush();
                    sink.onProgress(entry.id, completed, entry.sizeBytes, "paused");
                    return "Paused.";
                }
                output.write(chunk, 0, read);
                completed += read;
                // A quarter of a megabyte, so the bar moves rather than jumps.
                if (completed - lastReport > 262_144L) {
                    lastReport = completed;
                    sink.onProgress(entry.id, completed, entry.sizeBytes, "downloading");
                }
            }
            output.flush();
            WorkspaceStore.closeQuietly(output);
            output = null;

            sink.onProgress(entry.id, entry.sizeBytes, entry.sizeBytes, "verifying");
            String digest = sha256(partial);
            if (!digest.equalsIgnoreCase(entry.sha256)) {
                partial.delete();
                sink.onProgress(entry.id, 0L, entry.sizeBytes, "checksum:failed:" + digest);
                return "The file that arrived hashes to " + shortHash(digest) + ", not "
                    + shortHash(entry.sha256) + ". It was deleted.";
            }
            sink.onProgress(entry.id, entry.sizeBytes, entry.sizeBytes, "checksum:ok:" + digest);
            if (target.exists()) {
                target.delete();
            }
            if (!partial.renameTo(target)) {
                return "Could not move the verified model into place.";
            }
            sink.onProgress(entry.id, entry.sizeBytes, entry.sizeBytes, "done");
            return null;
        } catch (IOException error) {
            return error.getMessage() == null ? "The download failed." : error.getMessage();
        } catch (Exception error) {
            return "The download failed: " + error;
        } finally {
            downloadingId = "";
            WorkspaceStore.closeQuietly(input);
            WorkspaceStore.closeQuietly(output);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Enough of a hash to compare by eye, without a wall of hex. */
    public static String shortHash(String hex) {
        if (hex == null || hex.length() < 16) {
            return hex == null ? "" : hex;
        }
        return hex.substring(0, 8) + "…" + hex.substring(hex.length() - 8);
    }

    /** Read the parameter count out of a GGUF header, for an imported file. */
    public static String describeGguf(File file) {
        java.io.RandomAccessFile handle = null;
        try {
            handle = new java.io.RandomAccessFile(file, "r");
            byte[] magic = new byte[4];
            handle.readFully(magic);
            if (magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
                return "not a gguf";
            }
            long size = file.length();
            // Quantised weights land near one byte per parameter at Q8, half that
            // at Q4. Without reading the whole metadata block this is the honest
            // answer: a size, not a claim about the architecture.
            return String.format(Locale.US, "%.1f GB on disk", size / (1024.0 * 1024.0 * 1024.0));
        } catch (Exception error) {
            return "unreadable";
        } finally {
            WorkspaceStore.closeQuietly(handle);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        java.io.FileInputStream input = new java.io.FileInputStream(file);
        try {
            byte[] chunk = new byte[128 * 1024];
            int read;
            while ((read = input.read(chunk)) > 0) {
                digest.update(chunk, 0, read);
            }
        } finally {
            WorkspaceStore.closeQuietly(input);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(String.format(Locale.US, "%02x", value));
        }
        return hex.toString();
    }
}
