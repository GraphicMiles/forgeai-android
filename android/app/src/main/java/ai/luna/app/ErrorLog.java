package ai.luna.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * What happened, kept where a person can read it.
 *
 * Two tiers, one file. Failures are written to disk, because a failure that
 * only printed to the console is a failure nobody can report. Everything else —
 * bridge calls, HTTP round trips, engine steps — lives in memory only, as a
 * ring of the last few hundred lines, so the debug panel can show the run as it
 * happens without writing a megabyte of noise to storage every minute.
 */
public final class ErrorLog {

    private static final int MAX_ENTRIES = 50;
    private static final int MAX_LIVE = 400;
    private static final String FILE = "errors.json";

    public static final String ERROR = "error";
    public static final String WARN = "warn";
    public static final String INFO = "info";

    /** Told about every line, so the panel updates while the job runs. */
    public interface Listener {
        void onLine(JSONObject line);
    }

    /**
     * Static call sites — the provider layer is all static methods and has no
     * business being handed a logger — reach the live log through here.
     */
    private static volatile ErrorLog shared;

    private final Context context;
    private final Object lock = new Object();
    private final Deque<JSONObject> live = new ArrayDeque<JSONObject>();
    private volatile Listener listener;
    private long seq;

    public ErrorLog(Context context) {
        this.context = context.getApplicationContext();
        shared = this;
    }

    public void listen(Listener target) {
        this.listener = target;
    }

    private File file() {
        return new File(context.getFilesDir(), FILE);
    }

    // --- writing -------------------------------------------------------------

    /** Never throws. A logger that can fail is worse than no logger. */
    public void record(String where, String what) {
        line(ERROR, where, what);
    }

    public void record(String where, Throwable error) {
        String message = error == null ? "" : error.getMessage();
        record(where, message == null || message.isEmpty() ? String.valueOf(error) : message);
    }

    /** Background detail. Memory only. */
    public void note(String where, String what) {
        line(INFO, where, what);
    }

    public void warn(String where, String what) {
        line(WARN, where, what);
    }

    /** From a static context. Silently does nothing before the app is up. */
    public static void tap(String level, String where, String what) {
        ErrorLog target = shared;
        if (target != null) {
            target.line(level, where, what);
        }
    }

    public static void tapNote(String where, String what) {
        tap(INFO, where, what);
    }

    public static void tapFail(String where, String what) {
        tap(ERROR, where, what);
    }

    private void line(String level, String where, String what) {
        JSONObject entry = new JSONObject();
        try {
            synchronized (lock) {
                seq++;
                entry.put("n", seq);
                entry.put("at", System.currentTimeMillis());
                entry.put("level", level == null ? INFO : level);
                entry.put("side", "java");
                entry.put("where", where == null || where.isEmpty() ? "unknown" : where);
                entry.put("what", clip(what));
                live.addFirst(entry);
                while (live.size() > MAX_LIVE) {
                    live.removeLast();
                }
                if (ERROR.equals(level)) {
                    persist(entry);
                }
            }
        } catch (Exception ignored) {
            return;
        }
        Listener target = listener;
        if (target != null) {
            try {
                target.onLine(entry);
            } catch (Exception ignored) {
                // The panel is not allowed to break the app.
            }
        }
    }

    /** One line is one line. A stack trace pasted whole is unreadable. */
    private static String clip(String what) {
        if (what == null || what.isEmpty()) {
            return "no detail";
        }
        String value = what.replace('\n', ' ').trim();
        return value.length() > 600 ? value.substring(0, 600) + "…" : value;
    }

    private void persist(JSONObject entry) {
        try {
            JSONArray current = readArray();
            JSONArray next = new JSONArray();
            next.put(entry);
            for (int index = 0; index < current.length() && next.length() < MAX_ENTRIES; index++) {
                next.put(current.get(index));
            }
            write(next);
        } catch (Exception ignored) {
            // Nothing sensible to do here.
        }
    }

    // --- reading -------------------------------------------------------------

    /** The failures that survived a restart. */
    public JSONArray entries() {
        synchronized (lock) {
            return readArray();
        }
    }

    /** Everything this session, newest first. */
    public JSONArray lines() {
        synchronized (lock) {
            JSONArray array = new JSONArray();
            for (JSONObject entry : live) {
                array.put(entry);
            }
            return array;
        }
    }

    public void clear() {
        synchronized (lock) {
            live.clear();
            File target = file();
            if (target.exists()) {
                target.delete();
            }
        }
    }

    private JSONArray readArray() {
        File target = file();
        if (!target.exists()) {
            return new JSONArray();
        }
        FileInputStream input = null;
        try {
            input = new FileInputStream(target);
            byte[] buffer = new byte[(int) target.length()];
            int read = input.read(buffer);
            if (read <= 0) {
                return new JSONArray();
            }
            return new JSONArray(new String(buffer, 0, read, StandardCharsets.UTF_8));
        } catch (Exception error) {
            return new JSONArray();
        } finally {
            WorkspaceStore.closeQuietly(input);
        }
    }

    private void write(JSONArray array) throws JSONException {
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file());
            output.write(array.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Same reasoning as above.
        } finally {
            WorkspaceStore.closeQuietly(output);
        }
    }

    /**
     * A URL fit to show someone. Keys travel in the query string on Gemini, and
     * a debug panel that leaks the key is a debug panel that ends up in a
     * screenshot.
     */
    public static String safeUrl(String url) {
        if (url == null) {
            return "";
        }
        int cut = url.indexOf("?key=");
        if (cut < 0) {
            cut = url.indexOf("&key=");
        }
        return cut < 0 ? url : url.substring(0, cut) + "?key=…";
    }
}
