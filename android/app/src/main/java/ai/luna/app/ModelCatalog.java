package ai.luna.app;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Sorting and light filtering for a provider's model catalogue.
 *
 * <p>A /models listing mixes chat models with speech, embedding, moderation and
 * image models. Luna does not pretend to know a provider's taxonomy — the whole
 * list is always shown — but putting sixty ids up with "whisper" and
 * "text-embedding" scattered through the middle is a worse default than putting
 * the ones that can hold a conversation first.
 *
 * <p>Pure string logic, so it is tested on a plain JVM rather than guessed at.
 */
public final class ModelCatalog {

    /** Substrings that mean "this model does not do chat". */
    private static final String[] NOT_CHAT = {
        "whisper", "tts", "speech", "voice", "audio", "transcribe", "realtime",
        "orpheus", "playai", "canary", "kokoro", "bark", "musicgen",
        "embed", "embedding", "rerank", "moderation", "guard", "safeguard", "prompt-guard",
        "image", "dall-e", "imagen", "veo", "stable-diffusion", "sd3", "flux",
        "vision-encoder", "sora", "clip", "search-", "-edit", "ocr",
    };

    private ModelCatalog() {
    }

    /** True if the id looks like something you can send a prompt to. */
    public static boolean looksLikeChatModel(String id) {
        if (id == null || id.trim().isEmpty()) {
            return false;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        for (String marker : NOT_CHAT) {
            if (lower.contains(marker)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Chat-capable models first, alphabetically, then everything else. Nothing
     * is removed: someone who wants an unusual model can still find it.
     */
    public static List<String> ordered(List<String> ids) {
        List<String> chat = new ArrayList<>();
        List<String> other = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                if (id == null || id.trim().isEmpty()) {
                    continue;
                }
                if (looksLikeChatModel(id)) {
                    chat.add(id.trim());
                } else {
                    other.add(id.trim());
                }
            }
        }
        Collections.sort(chat, String.CASE_INSENSITIVE_ORDER);
        Collections.sort(other, String.CASE_INSENSITIVE_ORDER);
        List<String> out = new ArrayList<>(chat.size() + other.size());
        out.addAll(chat);
        out.addAll(other);
        return out;
    }

    /** The same ordering, straight into the array the bridge hands to Dart. */
    public static JSONArray orderedArray(List<String> ids) {
        JSONArray out = new JSONArray();
        for (String id : ordered(ids)) {
            out.put(id);
        }
        return out;
    }

    /** How many of these look chat-capable — used to caption the picker. */
    public static int chatCount(List<String> ids) {
        int count = 0;
        if (ids != null) {
            for (String id : ids) {
                if (looksLikeChatModel(id)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Gemini reports names as "models/gemini-2.0-flash"; the request path wants
     * the bare id. Trimmed once, here, rather than at three call sites.
     */
    public static String stripPrefix(String name) {
        if (name == null) {
            return "";
        }
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }
}
