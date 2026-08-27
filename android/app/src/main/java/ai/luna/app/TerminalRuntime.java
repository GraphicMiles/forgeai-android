package ai.luna.app;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(name = "TerminalRuntime")
public class TerminalRuntime extends Plugin {
    private static final int MAX_OUTPUT_CHARS = 1_000_000;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    private File terminalHome() throws Exception {
        File home = new File(getContext().getFilesDir(), "terminal-home").getCanonicalFile();
        if (!home.exists() && !home.mkdirs()) throw new IllegalArgumentException("Unable to create terminal home.");
        return home;
    }

    private File safeWorkingDirectory(String requested) throws Exception {
        File root = getContext().getFilesDir().getCanonicalFile();
        File directory = requested == null || requested.trim().isEmpty() ? terminalHome() : new File(requested).getCanonicalFile();
        if (!directory.toPath().startsWith(root.toPath())) throw new IllegalArgumentException("Terminal working directory must remain inside Luna app storage.");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalArgumentException("Unable to create terminal working directory.");
        if (!directory.isDirectory()) throw new IllegalArgumentException("Terminal working directory is not a folder.");
        return directory;
    }

    @PluginMethod
    public void execute(PluginCall call) {
        if (!AutonomyRuntime.isEnabled(getContext())) { call.reject("Full Autonomous mode is disabled.", "AUTONOMY_DISABLED"); return; }
        String command = call.getString("command", "").trim();
        if (command.isEmpty()) { call.reject("A shell command is required.", "EMPTY_COMMAND"); return; }
        int timeoutSeconds = Math.min(Math.max(call.getInt("timeoutSeconds", 120), 1), 600);
        String requestId = call.getString("requestId", UUID.randomUUID().toString());
        executor.execute(() -> {
            long started = System.currentTimeMillis();
            Process process = null;
            try {
                File cwd = safeWorkingDirectory(call.getString("cwd", ""));
                ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", command);
                builder.directory(cwd);
                builder.redirectErrorStream(true);
                Map<String, String> environment = builder.environment();
                environment.clear();
                environment.put("PATH", "/system/bin:/system/xbin:/vendor/bin:/product/bin");
                environment.put("HOME", terminalHome().getAbsolutePath());
                environment.put("TMPDIR", getContext().getCacheDir().getAbsolutePath());
                environment.put("LANG", "C.UTF-8");
                process = builder.start();
                processes.put(requestId, process);
                StringBuilder output = new StringBuilder();
                java.util.concurrent.atomic.AtomicBoolean truncated = new java.util.concurrent.atomic.AtomicBoolean(false);
                Process running = process;
                Thread readerThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(running.getInputStream()))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            synchronized (output) {
                                if (output.length() + line.length() + 1 <= MAX_OUTPUT_CHARS) output.append(line).append('\n');
                                else truncated.set(true);
                            }
                            JSObject event = new JSObject();
                            event.put("requestId", requestId); event.put("text", line + "\n");
                            notifyListeners("terminalOutput", event);
                        }
                    } catch (Exception ignored) {}
                });
                readerThread.start();
                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) { process.destroyForcibly(); readerThread.join(2000); throw new IllegalArgumentException("Command timed out after " + timeoutSeconds + " seconds."); }
                readerThread.join(2000);
                JSObject result = new JSObject();
                result.put("requestId", requestId);
                result.put("command", command);
                result.put("cwd", cwd.getAbsolutePath());
                result.put("exitCode", process.exitValue());
                result.put("output", output.toString());
                result.put("truncated", truncated.get());
                result.put("durationMs", System.currentTimeMillis() - started);
                call.resolve(result);
            } catch (Exception error) {
                call.reject(error.getMessage(), "TERMINAL_FAILED");
            } finally {
                processes.remove(requestId);
                if (process != null && process.isAlive()) process.destroyForcibly();
            }
        });
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        String requestId = call.getString("requestId", "");
        Process process = processes.remove(requestId);
        if (process != null) process.destroyForcibly();
        JSObject result = new JSObject(); result.put("requestId", requestId); result.put("cancelled", process != null); call.resolve(result);
    }

    @PluginMethod
    public void getInfo(PluginCall call) {
        JSObject result = new JSObject();
        result.put("shell", "/system/bin/sh");
        result.put("sandbox", "app-private");
        result.put("root", false);
        result.put("activeProcesses", processes.size());
        JSArray unavailable = new JSArray();
        for (String name : new String[] { "git", "node", "npm", "python", "python3" }) {
            if (!new File("/system/bin/" + name).exists()) unavailable.put(name);
        }
        result.put("commonlyUnavailable", unavailable);
        call.resolve(result);
    }
}
