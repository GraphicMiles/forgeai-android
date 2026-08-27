package ai.luna.app;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The llama.cpp bridge. One model in memory at a time; generation runs on the
 * caller's worker thread and pushes UTF-8 safe chunks back through a sink.
 *
 * The native symbols are Java_ai_luna_app_OnDeviceRuntime_* — do not rename or
 * repackage this class without editing cpp/OnDeviceRuntime.cpp.
 */
public final class OnDeviceRuntime {

    static {
        System.loadLibrary("ondevice_runtime");
    }

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

    public static final int STATUS_COMPLETE = 0;
    public static final int STATUS_CANCELLED = 1;
    public static final int STATUS_MODEL_NOT_LOADED = 2;
    public static final int STATUS_PROMPT_TOO_LONG = 3;

    /** Receives decoded text as it is produced. */
    public interface TokenSink {
        void onToken(String text);
    }

    /** What one generation cost, for the Models screen. */
    public static final class Result {
        public final int status;
        public final long outputTokens;
        public final long promptTokens;
        public final long prefillMicros;
        public final long generationMicros;
        public final String text;

        Result(int status, long outputTokens, long promptTokens, long prefillMicros, long generationMicros, String text) {
            this.status = status;
            this.outputTokens = outputTokens;
            this.promptTokens = promptTokens;
            this.prefillMicros = prefillMicros;
            this.generationMicros = generationMicros;
            this.text = text;
        }

        public double tokensPerSecond() {
            if (generationMicros <= 0L) {
                return 0d;
            }
            return (outputTokens * 1_000_000d) / generationMicros;
        }

        public String failure() {
            switch (status) {
                case STATUS_COMPLETE:
                    return null;
                case STATUS_CANCELLED:
                    return "Stopped.";
                case STATUS_MODEL_NOT_LOADED:
                    return "No model is loaded.";
                case STATUS_PROMPT_TOO_LONG:
                    return "The conversation is longer than the model's context window.";
                default:
                    return "The runtime failed while generating (status " + status + ").";
            }
        }
    }

    private final Object lock = new Object();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    private String loadedModelId = "";
    private String loadedModelPath = "";
    private String requestId = "";
    private StringBuilder collected = new StringBuilder();
    private TokenSink sink;

    public boolean isLoaded() {
        synchronized (lock) {
            return !loadedModelPath.isEmpty();
        }
    }

    public String loadedModelId() {
        synchronized (lock) {
            return loadedModelId;
        }
    }

    /** Blocking. Call from a worker thread. */
    public boolean load(String modelId, File file) {
        if (file == null || !file.exists()) {
            return false;
        }
        synchronized (lock) {
            if (file.getAbsolutePath().equals(loadedModelPath)) {
                return true;
            }
        }
        boolean ok = nativeLoad(file.getAbsolutePath());
        synchronized (lock) {
            loadedModelPath = ok ? file.getAbsolutePath() : "";
            loadedModelId = ok ? modelId : "";
        }
        return ok;
    }

    public void unload() {
        nativeUnload();
        synchronized (lock) {
            loadedModelPath = "";
            loadedModelId = "";
        }
    }

    /** Blocking. Call from a worker thread. */
    public Result generate(String prompt, int maxTokens, int contextTokens, int threads, TokenSink tokenSink) {
        String id = Long.toString(System.nanoTime());
        synchronized (lock) {
            requestId = id;
            sink = tokenSink;
            collected = new StringBuilder();
        }
        cancelled.set(false);

        byte[] promptBytes = prompt.getBytes(StandardCharsets.UTF_8);
        long[] raw = nativeGenerate(this, promptBytes, maxTokens, contextTokens, threads, id);

        String text;
        synchronized (lock) {
            text = collected.toString();
            requestId = "";
            sink = null;
        }
        if (raw == null || raw.length < 5) {
            return new Result(9, 0L, 0L, 0L, 0L, text);
        }
        return new Result((int) raw[0], raw[1], raw[2], raw[3], raw[4], text);
    }

    public void cancel() {
        cancelled.set(true);
        String id;
        synchronized (lock) {
            id = requestId;
        }
        if (!id.isEmpty()) {
            nativeCancel(id);
        }
    }

    // --- called from JNI -----------------------------------------------------

    @SuppressWarnings("unused")
    private boolean isCancellationRequested(String id) {
        synchronized (lock) {
            if (!id.equals(requestId)) {
                return true;
            }
        }
        return cancelled.get();
    }

    @SuppressWarnings("unused")
    private void onNativeToken(String id, byte[] utf8) {
        if (utf8 == null || utf8.length == 0) {
            return;
        }
        TokenSink target;
        synchronized (lock) {
            if (!id.equals(requestId)) {
                return;
            }
            String chunk = new String(utf8, StandardCharsets.UTF_8);
            collected.append(chunk);
            target = sink;
            if (target != null) {
                target.onToken(chunk);
            }
        }
    }
}
