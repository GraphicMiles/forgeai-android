package ai.luna.app;

import android.os.Build;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@CapacitorPlugin(name = "OnDeviceRuntime")
public class OnDeviceRuntime extends Plugin {
    static { System.loadLibrary("ondevice_runtime"); }

    private static native boolean nativeLoad(String path);
    private static native void nativeUnload();
    private static native long[] nativeGenerate(
        OnDeviceRuntime runtime,
        byte[] promptUtf8,
        int maxTokens,
        int contextTokens,
        int threads,
        String requestId
    );
    private static native void nativeCancel(String requestId);

    private static final int STATUS_COMPLETE = 0;
    private static final int STATUS_CANCELLED = 1;
    private static final int STATUS_MODEL_NOT_LOADED = 2;
    private static final int STATUS_PROMPT_TOO_LONG = 3;
    private static final int STATUS_TOKENIZE_FAILED = 4;
    private static final int STATUS_CONTEXT_CREATE_FAILED = 5;
    private static final int STATUS_PREFILL_FAILED = 6;
    private static final int STATUS_DECODE_FAILED = 7;
    private static final int STATUS_CALLBACK_FAILED = 8;

    private enum RuntimeState { IDLE, LOADING, READY, GENERATING, CANCELLING, UNLOADING, ERROR }

    private static final class GenerationRequest {
        final String id;
        final String modelId;
        final AtomicBoolean cancelRequested = new AtomicBoolean(false);
        final AtomicBoolean terminalEmitted = new AtomicBoolean(false);
        GenerationRequest(String id, String modelId) { this.id = id; this.modelId = modelId; }
    }

    private final Object stateLock = new Object();
    private final ExecutorService inferenceExecutor = Executors.newSingleThreadExecutor();
    private volatile GenerationRequest activeRequest = null;
    private volatile RuntimeState runtimeState = RuntimeState.IDLE;
    private volatile String loadedModelPath = null;
    private volatile String loadedModelId = null;
    private volatile long lastLoadMs = 0;

    private final Map<String, Boolean> pausedDownloads = new ConcurrentHashMap<>();
    private final Map<String, Thread> activeDownloads = new ConcurrentHashMap<>();
    private final Map<String, HttpURLConnection> activeConnections = new ConcurrentHashMap<>();

    private void emit(String eventName, JSObject payload) {
        notifyListeners(eventName, payload);
    }

    @PluginMethod
    public void getInfo(PluginCall call) {
        JSObject result = new JSObject();
        result.put("available", true);
        result.put("backend", "llama.cpp-cpu");
        result.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown");
        result.put("loaded", loadedModelPath != null);
        result.put("state", runtimeState.name());
        result.put("activeRequestId", activeRequest == null ? "" : activeRequest.id);
        result.put("loadedModelId", loadedModelId == null ? "" : loadedModelId);
        result.put("loadedPath", loadedModelPath == null ? "" : loadedModelPath);
        result.put("lastLoadMs", lastLoadMs);
        call.resolve(result);
    }

    private File safeModelFile(String path) throws Exception {
        File modelsDirectory = new File(getContext().getFilesDir(), "models").getCanonicalFile();
        File model = new File(path).getCanonicalFile();
        if (!model.toPath().startsWith(modelsDirectory.toPath()) || model.equals(modelsDirectory)) {
            throw new IllegalArgumentException("Model path is outside app-private model storage");
        }
        return model;
    }

    private void validateGguf(File model) throws Exception {
        if (!model.isFile()) throw new IllegalArgumentException("Model file does not exist: " + model.getAbsolutePath());
        if (model.length() < 4096) throw new IllegalArgumentException("Model file is too small or incomplete: " + model.length() + " bytes");
        try (RandomAccessFile input = new RandomAccessFile(model, "r")) {
            byte[] magic = new byte[4];
            input.readFully(magic);
            if (magic[0] != 'G' || magic[1] != 'G' || magic[2] != 'U' || magic[3] != 'F') {
                throw new IllegalArgumentException("Invalid GGUF header; file may be corrupted or not a GGUF model");
            }
        }
    }

    @PluginMethod
    public void load(PluginCall call) {
        final String modelId = call.getString("modelId", "");
        final String path = call.getString("path", "");
        if (path.isEmpty() || modelId.isEmpty()) { call.reject("Model id and path are required", "INVALID_MODEL"); return; }
        final File model;
        try {
            model = safeModelFile(path);
            validateGguf(model);
        } catch (Exception error) { call.reject(error.getMessage(), "INVALID_MODEL"); return; }

        synchronized (stateLock) {
            if (runtimeState == RuntimeState.GENERATING || runtimeState == RuntimeState.CANCELLING) {
                call.reject("Cannot load a model during generation", "RUNTIME_BUSY"); return;
            }
            if (runtimeState == RuntimeState.LOADING || runtimeState == RuntimeState.UNLOADING) {
                call.reject("A model lifecycle operation is already active", "RUNTIME_BUSY"); return;
            }
            if (model.getAbsolutePath().equals(loadedModelPath)) {
                loadedModelId = modelId;
                runtimeState = RuntimeState.READY;
                JSObject reused = new JSObject();
                reused.put("loaded", true); reused.put("reused", true); reused.put("modelId", modelId); reused.put("loadMs", lastLoadMs);
                call.resolve(reused);
                return;
            }
            runtimeState = RuntimeState.LOADING;
        }

        JSObject started = new JSObject();
        started.put("modelId", modelId);
        emit("modelLoadStarted", started);
        inferenceExecutor.execute(() -> {
            long start = System.nanoTime();
            boolean loaded = nativeLoad(model.getAbsolutePath());
            long loadMs = (System.nanoTime() - start) / 1_000_000L;
            synchronized (stateLock) {
                if (loaded) {
                    loadedModelPath = model.getAbsolutePath();
                    loadedModelId = modelId;
                    lastLoadMs = loadMs;
                    runtimeState = RuntimeState.READY;
                } else {
                    loadedModelPath = null;
                    loadedModelId = null;
                    runtimeState = RuntimeState.ERROR;
                }
            }
            if (!loaded) {
                JSObject failure = new JSObject();
                failure.put("modelId", modelId); failure.put("code", "MODEL_LOAD_FAILED"); failure.put("message", "llama.cpp rejected the GGUF model");
                emit("modelLoadError", failure);
                call.reject("llama.cpp rejected the GGUF model", "MODEL_LOAD_FAILED");
                return;
            }
            JSObject complete = new JSObject();
            complete.put("modelId", modelId); complete.put("loaded", true); complete.put("reused", false); complete.put("loadMs", loadMs);
            emit("modelLoadComplete", complete);
            call.resolve(complete);
        });
    }

    @PluginMethod
    public void unload(PluginCall call) {
        synchronized (stateLock) {
            if (runtimeState == RuntimeState.GENERATING || runtimeState == RuntimeState.CANCELLING) {
                call.reject("Cancel generation before unloading", "RUNTIME_BUSY"); return;
            }
            if (runtimeState == RuntimeState.LOADING || runtimeState == RuntimeState.UNLOADING) {
                call.reject("A model lifecycle operation is already active", "RUNTIME_BUSY"); return;
            }
            runtimeState = RuntimeState.UNLOADING;
        }
        inferenceExecutor.execute(() -> {
            nativeUnload();
            synchronized (stateLock) {
                runtimeState = RuntimeState.IDLE;
                loadedModelPath = null;
                loadedModelId = null;
                activeRequest = null;
            }
            call.resolve();
        });
    }

    @SuppressWarnings("unused")
    private boolean isCancellationRequested(String requestId) {
        GenerationRequest request = activeRequest;
        return request == null || !request.id.equals(requestId) || request.cancelRequested.get();
    }

    @SuppressWarnings("unused")
    private void onNativeToken(String requestId, byte[] utf8) {
        GenerationRequest request = activeRequest;
        if (request == null || !request.id.equals(requestId) || request.terminalEmitted.get() || utf8 == null || utf8.length == 0) return;
        JSObject token = new JSObject();
        token.put("requestId", requestId);
        token.put("modelId", request.modelId);
        token.put("token", new String(utf8, StandardCharsets.UTF_8));
        emit("generationToken", token);
    }

    private String statusCode(int status) {
        switch (status) {
            case STATUS_MODEL_NOT_LOADED: return "MODEL_NOT_LOADED";
            case STATUS_PROMPT_TOO_LONG: return "PROMPT_TOO_LONG";
            case STATUS_TOKENIZE_FAILED: return "TOKENIZE_FAILED";
            case STATUS_CONTEXT_CREATE_FAILED: return "CONTEXT_CREATE_FAILED";
            case STATUS_PREFILL_FAILED: return "PREFILL_FAILED";
            case STATUS_DECODE_FAILED: return "DECODE_FAILED";
            case STATUS_CALLBACK_FAILED: return "STREAM_CALLBACK_FAILED";
            default: return "NATIVE_GENERATION_FAILED";
        }
    }

    private String statusMessage(int status) {
        switch (status) {
            case STATUS_MODEL_NOT_LOADED: return "The model is not loaded.";
            case STATUS_PROMPT_TOO_LONG: return "The prompt and output reservation exceed the model context window.";
            case STATUS_TOKENIZE_FAILED: return "The prompt could not be tokenized.";
            case STATUS_CONTEXT_CREATE_FAILED: return "llama.cpp could not allocate the inference context.";
            case STATUS_PREFILL_FAILED: return "llama.cpp failed while processing the prompt.";
            case STATUS_DECODE_FAILED: return "llama.cpp failed while generating a token.";
            case STATUS_CALLBACK_FAILED: return "The native token stream could not be delivered.";
            default: return "Native generation failed.";
        }
    }

    private JSObject metrics(GenerationRequest request, long[] values, boolean cancelled) {
        long outputTokens = values.length > 1 ? values[1] : 0;
        long promptTokens = values.length > 2 ? values[2] : 0;
        double prefillMs = values.length > 3 ? values[3] / 1000.0 : 0;
        double generationMs = values.length > 4 ? values[4] / 1000.0 : 0;
        long contextTokens = values.length > 5 ? values[5] : 0;
        long threads = values.length > 6 ? values[6] : 0;
        double tokensPerSecond = generationMs > 0 ? outputTokens * 1000.0 / generationMs : 0;
        double prefillTokensPerSecond = prefillMs > 0 ? promptTokens * 1000.0 / prefillMs : 0;
        JSObject result = new JSObject();
        result.put("requestId", request.id);
        result.put("modelId", request.modelId);
        result.put("tokenCount", outputTokens);
        result.put("promptTokens", promptTokens);
        result.put("prefillMs", prefillMs);
        result.put("generationMs", generationMs);
        result.put("tokensPerSecond", tokensPerSecond);
        result.put("prefillTokensPerSecond", prefillTokensPerSecond);
        result.put("contextTokens", contextTokens);
        result.put("threads", threads);
        result.put("cancelled", cancelled);
        return result;
    }

    @PluginMethod
    public void generate(PluginCall call) {
        String prompt = call.getString("prompt", "");
        String requestId = call.getString("requestId", UUID.randomUUID().toString());
        String modelId = call.getString("modelId", loadedModelId == null ? "" : loadedModelId);
        int maxTokens = Math.min(Math.max(call.getInt("maxTokens", 128), 1), 1024);
        int contextTokens = Math.min(Math.max(call.getInt("contextTokens", 2048), 512), 8192);
        int threads = Math.min(Math.max(call.getInt("threads", 2), 1), 4);
        if (prompt.isEmpty()) { call.reject("A prompt is required", "EMPTY_PROMPT"); return; }
        if (requestId.isEmpty() || modelId.isEmpty()) { call.reject("Request id and model id are required", "INVALID_REQUEST"); return; }

        GenerationRequest request = new GenerationRequest(requestId, modelId);
        synchronized (stateLock) {
            if (runtimeState != RuntimeState.READY || activeRequest != null) { call.reject("A generation or lifecycle operation is already active", "RUNTIME_BUSY"); return; }
            if (loadedModelPath == null) { runtimeState = RuntimeState.IDLE; call.reject("The model is not loaded", "MODEL_NOT_LOADED"); return; }
            if (!modelId.equals(loadedModelId)) { call.reject("The requested model is not the loaded model", "MODEL_MISMATCH"); return; }
            activeRequest = request;
            runtimeState = RuntimeState.GENERATING;
        }

        JSObject started = new JSObject();
        started.put("requestId", requestId); started.put("modelId", modelId); started.put("contextTokens", contextTokens); started.put("maxOutputTokens", maxTokens);
        emit("generationStarted", started);

        inferenceExecutor.execute(() -> {
            long[] values;
            if (request.cancelRequested.get()) values = new long[] { STATUS_CANCELLED, 0, 0, 0, 0, contextTokens, threads };
            else values = nativeGenerate(this, prompt.getBytes(StandardCharsets.UTF_8), maxTokens, contextTokens, threads, requestId);
            if (values == null || values.length == 0) values = new long[] { STATUS_CALLBACK_FAILED, 0, 0, 0, 0, contextTokens, threads };
            int status = (int) values[0];
            if (!request.terminalEmitted.compareAndSet(false, true)) return;
            synchronized (stateLock) {
                if (activeRequest == request) activeRequest = null;
                runtimeState = loadedModelPath != null ? RuntimeState.READY : RuntimeState.IDLE;
            }
            if (status == STATUS_COMPLETE || status == STATUS_CANCELLED) {
                JSObject result = metrics(request, values, status == STATUS_CANCELLED);
                emit("generationComplete", result);
                call.resolve(result);
            } else {
                String code = statusCode(status);
                String message = statusMessage(status);
                JSObject error = metrics(request, values, false);
                error.put("code", code); error.put("message", message);
                emit("generationError", error);
                call.reject(message, code, error);
            }
        });
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        String requestId = call.getString("requestId", "");
        GenerationRequest request = activeRequest;
        if (request == null || !request.id.equals(requestId)) {
            JSObject result = new JSObject(); result.put("cancelled", false); result.put("reason", "request-not-active"); call.resolve(result); return;
        }
        request.cancelRequested.set(true);
        synchronized (stateLock) { if (activeRequest == request) runtimeState = RuntimeState.CANCELLING; }
        nativeCancel(requestId);
        JSObject result = new JSObject(); result.put("cancelled", true); result.put("requestId", requestId); call.resolve(result);
    }

    @PluginMethod
    public void deleteModel(PluginCall call) {
        synchronized (stateLock) {
            if (runtimeState == RuntimeState.GENERATING || runtimeState == RuntimeState.CANCELLING || runtimeState == RuntimeState.LOADING || runtimeState == RuntimeState.UNLOADING) {
                call.reject("The runtime is busy", "RUNTIME_BUSY"); return;
            }
        }
        String path = call.getString("path", "");
        try {
            File target = safeModelFile(path);
            if (loadedModelPath != null && target.getAbsolutePath().equals(loadedModelPath)) { call.reject("Unload the active model before deleting it", "MODEL_LOADED"); return; }
            if (target.exists() && (!target.isFile() || !target.delete())) { call.reject("Unable to delete model", "MODEL_DELETE_FAILED"); return; }
            call.resolve();
        } catch (Exception error) { call.reject("Unable to delete model safely", "MODEL_DELETE_FAILED"); }
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[262144];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value));
        return hash.toString();
    }

    @PluginMethod
    public void download(PluginCall call) {
        String urlString = call.getString("url", "");
        String requestedFilename = call.getString("filename", "model.gguf");
        String expectedSha = call.getString("sha256", "").toLowerCase(Locale.ROOT);
        String filename = requestedFilename.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!urlString.startsWith("https://")) { call.reject("An HTTPS model URL is required"); return; }
        if (!expectedSha.matches("^[a-f0-9]{64}$")) { call.reject("A trusted SHA-256 is required"); return; }
        if (!filename.equals(requestedFilename) || filename.startsWith(".") || !filename.toLowerCase(Locale.ROOT).endsWith(".gguf")) {
            call.reject("A safe GGUF filename is required"); return;
        }
        if (activeDownloads.containsKey(filename)) { call.reject("A download for this file is already in progress."); return; }
        pausedDownloads.remove(filename);

        Thread downloadThread = new Thread(() -> {
            try {
                File directory = new File(getContext().getFilesDir(), "models");
                if (!directory.exists() && !directory.mkdirs()) throw new Exception("Unable to create model directory");
                File target = new File(directory, filename).getCanonicalFile();
                File temp = new File(directory, filename + ".part").getCanonicalFile();
                if (!target.toPath().startsWith(directory.getCanonicalFile().toPath()) || !temp.toPath().startsWith(directory.getCanonicalFile().toPath())) throw new Exception("Unsafe model path");
                long existingBytes = temp.exists() ? temp.length() : 0;
                HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
                connection.setConnectTimeout(15000); connection.setReadTimeout(120000); connection.setInstanceFollowRedirects(true);
                if (existingBytes > 0) connection.setRequestProperty("Range", "bytes=" + existingBytes + "-");
                connection.connect(); activeConnections.put(filename, connection);
                int code = connection.getResponseCode();
                boolean supportsResume = code == 206;
                long totalBytes = connection.getContentLengthLong();
                if (!supportsResume && existingBytes > 0) { existingBytes = 0; temp.delete(); }
                if (totalBytes > 0) totalBytes += existingBytes;
                if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) throw new Exception("Download redirected to a non-HTTPS URL");
                if (code < 200 || code >= 300) throw new Exception("Download failed: HTTP " + code);
                long downloaded = existingBytes;
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(temp, supportsResume && existingBytes > 0)) {
                    byte[] buffer = new byte[256 * 1024]; int count; long lastEmit = 0;
                    while ((count = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) { output.flush(); temp.delete(); throw new InterruptedException("Download cancelled"); }
                        if (pausedDownloads.containsKey(filename)) { output.flush(); JSObject paused = new JSObject(); paused.put("paused", true); paused.put("filename", filename); paused.put("completed", downloaded); call.resolve(paused); return; }
                        output.write(buffer, 0, count); downloaded += count;
                        long now = System.currentTimeMillis();
                        if (now - lastEmit > 500) { lastEmit = now; JSObject progress = new JSObject(); progress.put("filename", filename); progress.put("progress", totalBytes > 0 ? (int) (downloaded * 100 / totalBytes) : 0); progress.put("completed", downloaded); progress.put("total", totalBytes); notifyListeners("downloadProgress", progress); }
                    }
                } finally { activeConnections.remove(filename); connection.disconnect(); }
                if (!expectedSha.equals(sha256(temp))) { temp.delete(); throw new Exception("Downloaded model failed SHA-256 verification"); }
                if (target.exists() && !target.delete()) throw new Exception("Unable to replace existing model file");
                if (!temp.renameTo(target)) throw new Exception("Unable to finalize model file");
                JSObject progress = new JSObject(); progress.put("filename", filename); progress.put("progress", 100); progress.put("completed", downloaded); progress.put("total", downloaded); notifyListeners("downloadProgress", progress);
                JSObject result = new JSObject(); result.put("path", target.getAbsolutePath()); result.put("size", target.length()); result.put("sha256", expectedSha); result.put("verified", true); call.resolve(result);
            } catch (Exception error) { call.reject(error.getMessage()); }
            finally { activeDownloads.remove(filename); pausedDownloads.remove(filename); }
        });
        if (activeDownloads.putIfAbsent(filename, downloadThread) != null) { call.reject("A download for this file is already in progress."); return; }
        downloadThread.start();
    }

    @PluginMethod
    public void pauseDownload(PluginCall call) {
        String filename = call.getString("filename", "");
        if (filename.isEmpty()) { call.reject("A filename is required"); return; }
        pausedDownloads.put(filename, true);
        HttpURLConnection connection = activeConnections.get(filename);
        if (connection != null) connection.disconnect();
        call.resolve();
    }

    @PluginMethod
    public void cancelDownload(PluginCall call) {
        String filename = call.getString("filename", "");
        Thread thread = activeDownloads.remove(filename);
        if (thread != null) thread.interrupt();
        HttpURLConnection connection = activeConnections.remove(filename);
        if (connection != null) connection.disconnect();
        pausedDownloads.remove(filename);
        call.resolve();
    }
}
