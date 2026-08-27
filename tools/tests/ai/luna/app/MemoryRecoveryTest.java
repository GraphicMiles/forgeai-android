package ai.luna.app;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs on the JVM against the real AgentEngine code. Covers the two things the
 * user asked about: what Luna remembers, and what happens when a job is
 * stopped or killed halfway through.
 */
public final class MemoryRecoveryTest {

    private static int failures = 0;

    public static void main(String[] args) {
        List<JSONObject> chat = new ArrayList<>();
        chat.add(message("user", "Tidy the notes folder", null));
        chat.add(message("observation", "list_files → 40 files", "list_files"));
        chat.add(message("assistant", "Done. Renamed 4 files.", null));

        // --- memory ---
        check("the whole chat fits when the budget is large",
            AgentEngine.tail(chat, 6000).size() == 3);
        check("the oldest message is dropped first",
            AgentEngine.tail(chat, 30).get(0).optString("content").startsWith("Done"));
        check("a budget smaller than one message still returns that message",
            AgentEngine.tail(chat, 1).size() == 1);
        check("order is oldest first",
            AgentEngine.tail(chat, 6000).get(0).optString("role").equals("user"));
        check("the instruction is found however far back it is",
            AgentEngine.lastUserInstruction(chat).equals("Tidy the notes folder"));

        // --- a finished job ---
        check("a finished job is not dangling", !AgentEngine.dangling(chat));
        check("a finished job offers no carry on", !AgentEngine.resumable(chat));

        // --- killed with the app ---
        List<JSONObject> killed = new ArrayList<>(chat);
        killed.add(message("user", "Now sort them by date", null));
        killed.add(message("observation", "list_files → 40 files", "list_files"));
        check("a turn with no answer is dangling", AgentEngine.dangling(killed));

        killed.add(message("assistant", "That job stopped when Luna closed.", "interrupted"));
        check("the note closes the dangling turn", !AgentEngine.dangling(killed));
        check("an interrupted job can be carried on", AgentEngine.resumable(killed));
        check("carrying on knows what was asked",
            AgentEngine.lastUserInstruction(killed).equals("Now sort them by date"));
        check("work already done is still in the window",
            AgentEngine.tail(killed, 6000).size() == 6);

        // --- stopped by hand ---
        List<JSONObject> stopped = new ArrayList<>(chat);
        stopped.add(message("user", "Rewrite every heading", null));
        stopped.add(message("observation", "read_file → notes.md", "read_file"));
        stopped.add(message("assistant", "Stopped.", "stopped"));
        check("a stopped job can be carried on", AgentEngine.resumable(stopped));
        check("the step that worked survives the stop",
            AgentEngine.tail(stopped, 6000).get(4).optString("content").contains("read_file"));

        // --- a job cut short by a limit ---
        List<JSONObject> cutShort = new ArrayList<>(chat);
        cutShort.add(message("user", "Read all 200 files", null));
        cutShort.add(message("assistant", "I hit the time limit.", "stopped"));
        check("a job cut short by a limit can be carried on", AgentEngine.resumable(cutShort));

        // --- nothing to carry on ---
        check("an empty chat offers nothing", !AgentEngine.resumable(new ArrayList<JSONObject>()));
        List<JSONObject> noOrder = new ArrayList<>();
        noOrder.add(message("assistant", "Stopped.", "stopped"));
        check("a stop with no instruction offers nothing", !AgentEngine.resumable(noOrder));

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static JSONObject message(String role, String content, String meta) {
        JSONObject out = new JSONObject();
        try {
            out.put("role", role);
            out.put("content", content);
            if (meta != null) {
                out.put("meta", meta);
            }
        } catch (Exception ignored) {
            // Not possible with these keys.
        }
        return out;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  pass  " : "  FAIL  ") + what);
        if (!ok) {
            failures++;
        }
    }
}
