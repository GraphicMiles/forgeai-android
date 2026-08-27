package ai.luna.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * What went wrong, kept on disk.
 *
 * A failure that only prints to the console is a failure nobody can report. This
 * is a small ring buffer in the app's own storage: the last fifty things that
 * broke, with where and when, readable from Settings.
 */
public final class ErrorLog {

    private static final int MAX_ENTRIES = 50;
    private static final String FILE = "errors.json";

    private final Context context;
    private final Object lock = new Object();

    public ErrorLog(Context context) {
        this.context = context.getApplicationContext();
    }

    private File file() {
        return new File(context.getFilesDir(), FILE);
    }

    /** Never throws. A logger that can fail is worse than no logger. */
    public void record(String where, String what) {
        synchronized (lock) {
            try {
                JSONArray current = readArray();
                JSONObject entry = new JSONObject();
                entry.put("at", System.currentTimeMillis());
                entry.put("where", where == null ? "unknown" : where);
                entry.put("what", what == null ? "no detail" : what);
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
    }

    public void record(String where, Throwable error) {
        String message = error == null ? "" : error.getMessage();
        record(where, message == null || message.isEmpty() ? String.valueOf(error) : message);
    }

    public JSONArray entries() {
        synchronized (lock) {
            return readArray();
        }
    }

    public void clear() {
        synchronized (lock) {
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
}
