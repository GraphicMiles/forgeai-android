package ai.luna.app;

import ai.luna.contracts.MemoryKind;
import ai.luna.contracts.MemoryProvider;
import ai.luna.contracts.MemoryRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The memories that have to survive being closed.
 *
 * <p>One JSON file per kind, under the app's own storage — not in the person's
 * folder, which is theirs and not a database. Each file is capped, and the cap
 * drops the least important record rather than the oldest: what somebody
 * deliberately told Luna should outlive what she noticed in passing.
 */
public final class FileMemory implements MemoryProvider {

    private static final int LIMIT = 500;

    private final File root;
    private final ErrorLog errors;

    public FileMemory(File filesDir, ErrorLog errors) {
        this.root = new File(filesDir, "memory");
        this.errors = errors;
    }

    @Override
    public String id() {
        return "memory.files";
    }

    @Override
    public List<String> kinds() {
        return Collections.unmodifiableList(Arrays.asList(
            MemoryKind.LONG_TERM, MemoryKind.KNOWLEDGE, MemoryKind.EXECUTION));
    }

    @Override
    public synchronized void write(MemoryRecord record) {
        if (record == null || !kinds().contains(record.kind)) {
            return;
        }
        List<MemoryRecord> held = read(record.kind, record.agentId);
        held.add(record);
        while (held.size() > LIMIT) {
            held.remove(leastWorthKeeping(held));
        }
        save(record.kind, record.agentId, held);
    }

    @Override
    public synchronized List<MemoryRecord> all(String kind, String agentId) {
        List<MemoryRecord> held = read(kind, agentId);
        Collections.reverse(held);
        return held;
    }

    @Override
    public synchronized boolean forget(String id) {
        for (String kind : kinds()) {
            File folder = new File(root, kind);
            File[] files = folder.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                String agent = file.getName().replace(".json", "");
                List<MemoryRecord> held = read(kind, agent);
                for (int index = 0; index < held.size(); index++) {
                    if (held.get(index).id.equals(id)) {
                        held.remove(index);
                        save(kind, agent, held);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public synchronized int clear(String kind, String agentId) {
        List<MemoryRecord> held = read(kind, agentId);
        File file = fileFor(kind, agentId);
        if (file.exists() && !file.delete()) {
            save(kind, agentId, new ArrayList<MemoryRecord>());
        }
        return held.size();
    }

    // --- the file itself ------------------------------------------------------

    private List<MemoryRecord> read(String kind, String agentId) {
        List<MemoryRecord> out = new ArrayList<>();
        File file = fileFor(kind, agentId);
        if (!file.exists()) {
            return out;
        }
        RandomAccessFile handle = null;
        try {
            handle = new RandomAccessFile(file, "r");
            byte[] bytes = new byte[(int) handle.length()];
            handle.readFully(bytes);
            JSONArray array = new JSONArray(new String(bytes, Charset.forName("UTF-8")));
            for (int index = 0; index < array.length(); index++) {
                JSONObject row = array.optJSONObject(index);
                if (row != null) {
                    out.add(MemoryRecord.fromJson(row));
                }
            }
        } catch (Exception error) {
            // A corrupt memory file means no memories of that kind, never a
            // crash and never a half-read list.
            errors.record("memory:" + kind, error);
            return new ArrayList<>();
        } finally {
            close(handle);
        }
        return out;
    }

    private void save(String kind, String agentId, List<MemoryRecord> held) {
        File file = fileFor(kind, agentId);
        File folder = file.getParentFile();
        if (folder != null && !folder.exists() && !folder.mkdirs()) {
            errors.warn("memory", "could not make " + kind + " storage");
            return;
        }
        JSONArray array = new JSONArray();
        for (MemoryRecord record : held) {
            try {
                array.put(record.toJson());
            } catch (Exception ignored) {
                // One unwritable record is not worth losing the rest over.
            }
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            out.write(array.toString().getBytes(Charset.forName("UTF-8")));
        } catch (Exception error) {
            errors.record("memory:" + kind, error);
        } finally {
            close(out);
        }
    }

    private File fileFor(String kind, String agentId) {
        String agent = agentId == null || agentId.isEmpty() ? "luna" : agentId;
        return new File(new File(root, kind), agent.replace('/', '-') + ".json");
    }

    private int leastWorthKeeping(List<MemoryRecord> held) {
        int worst = 0;
        for (int index = 1; index < held.size(); index++) {
            MemoryRecord candidate = held.get(index);
            MemoryRecord current = held.get(worst);
            if (candidate.importance < current.importance
                || (candidate.importance == current.importance && candidate.at < current.at)) {
                worst = index;
            }
        }
        return worst;
    }

    private void close(java.io.Closeable handle) {
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (Exception ignored) {
            // Closing is best effort.
        }
    }
}
