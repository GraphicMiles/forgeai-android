package ai.luna.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * The provider layer's decisions, run for real on a plain JVM.
 *
 * <p>Nothing here touches the network. What is being checked is the part that
 * decides where a request goes, how the key is attached, which models are worth
 * showing first, and what a failure is called in front of a person — the parts
 * that used to be assumptions.
 */
public final class ProviderConfigTest {

    private static int failures = 0;

    public static void main(String[] args) {
        addresses();
        catalogue();
        shapes();
        configuration();
        conversation();
        words();
        greetings();
        invented();
        gate();

        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    private static void addresses() {
        check("https is fine", EndpointPolicy.isUsable("https://api.openai.com/v1"));
        check("http on the internet is refused",
            !EndpointPolicy.isUsable("http://api.openai.com/v1"));
        check("http to your own machine is allowed",
            EndpointPolicy.isUsable("http://192.168.1.10:1234/v1"));
        check("http to localhost is allowed", EndpointPolicy.isUsable("http://localhost:11434/v1"));
        check("http to 10.x is allowed", EndpointPolicy.isUsable("http://10.0.0.4:1234/v1"));
        check("http to 172.16.x is allowed", EndpointPolicy.isUsable("http://172.16.3.9:1234/v1"));
        check("http to 172.32.x is not private",
            !EndpointPolicy.isUsable("http://172.32.3.9:1234/v1"));
        check("an empty address is refused", !EndpointPolicy.isUsable(""));
        check("a key in the address is refused",
            !EndpointPolicy.isUsable("https://user:pass@api.example.com/v1"));
        check("a query string in the base is refused",
            !EndpointPolicy.isUsable("https://api.example.com/v1?key=abc"));
        check("ftp is refused", !EndpointPolicy.isUsable("ftp://files.example.com"));
        check("the refusal is a sentence",
            EndpointPolicy.reason("http://api.example.com").contains("own network"));
        check("trailing slashes come off",
            EndpointPolicy.tidy("https://api.example.com/v1///").equals("https://api.example.com/v1"));
    }

    private static void catalogue() {
        List<String> mixed = Arrays.asList(
            "whisper-large-v3", "gpt-4o-mini", "text-embedding-3-small", "claude-3-5-haiku",
            "dall-e-3", "  ", "llama-3.1-8b-instant");
        List<String> ordered = ModelCatalog.ordered(mixed);
        check("nothing is thrown away", ordered.size() == 6);
        check("chat models come first", ordered.get(0).equals("claude-3-5-haiku"));
        check("speech models sink", ordered.indexOf("whisper-large-v3") > 2);
        check("embeddings sink", ordered.indexOf("text-embedding-3-small") > 2);
        check("three of those hold a conversation", ModelCatalog.chatCount(mixed) == 3);
        check("gemini's prefix comes off",
            ModelCatalog.stripPrefix("models/gemini-2.0-flash").equals("gemini-2.0-flash"));
        check("a bare id is left alone",
            ModelCatalog.stripPrefix("gpt-4o").equals("gpt-4o"));
        check("an empty id is not a chat model", !ModelCatalog.looksLikeChatModel(" "));
    }

    private static void shapes() {
        check("claude means anthropic",
            CloudProvider.normaliseKind("claude").equals(CloudProvider.ANTHROPIC));
        check("google means gemini",
            CloudProvider.normaliseKind("Google").equals(CloudProvider.GEMINI));
        check("anything unknown is openai-shaped",
            CloudProvider.normaliseKind("mystery").equals(CloudProvider.OPENAI));
        check("openai uses a bearer token",
            CloudProvider.defaultAuthStyle("openai").equals(CloudProvider.AUTH_BEARER));
        check("anthropic uses a header",
            CloudProvider.defaultAuthStyle("anthropic").equals(CloudProvider.AUTH_HEADER));
        check("gemini puts the key in the url",
            CloudProvider.defaultAuthStyle("gemini").equals(CloudProvider.AUTH_QUERY));
        check("anthropic's header is x-api-key",
            CloudProvider.defaultAuthName("anthropic", CloudProvider.AUTH_HEADER)
                .equals("x-api-key"));
        check("a query key is called key",
            CloudProvider.defaultAuthName("gemini", CloudProvider.AUTH_QUERY).equals("key"));
    }

    private static void configuration() {
        CloudProvider.Config old = new CloudProvider.Config(
            "https://api.openai.com/v1/", "sk-test", "gpt-4o-mini");
        check("a row from an older build is openai-shaped", old.kind.equals(CloudProvider.OPENAI));
        check("its key rides as a bearer token", old.authStyle.equals(CloudProvider.AUTH_BEARER));
        check("its address is tidied", old.baseUrl.equals("https://api.openai.com/v1"));
        check("a complete config has no problem", old.problem(true) == null);

        CloudProvider.Config noModel = new CloudProvider.Config(
            "anthropic", "https://api.anthropic.com/v1", "sk-ant", "", "", "", null);
        check("no model is a stated problem", noModel.problem(true) != null);
        check("but listing models does not need one", noModel.problem(false) == null);
        check("anthropic defaults to its own header", noModel.authName.equals("x-api-key"));

        CloudProvider.Config noKey = new CloudProvider.Config(
            "openai", "https://api.groq.com/openai/v1", "", "llama-3.1-8b", "", "", null);
        check("a hosted provider with no key says so", noKey.problem(true) != null);

        CloudProvider.Config lan = new CloudProvider.Config(
            "openai", "http://192.168.1.10:1234/v1", "", "qwen2.5", "", "", null);
        check("your own machine needs no key", lan.problem(true) == null);

        CloudProvider.Config gemini = new CloudProvider.Config(
            "gemini", "https://generativelanguage.googleapis.com/v1beta", "AIza",
            "models/gemini-2.0-flash", "", "", null);
        check("the model prefix is stripped once, here",
            gemini.model.equals("gemini-2.0-flash"));
        check("changing the model keeps everything else",
            gemini.withModel("gemini-2.5-pro").authStyle.equals(CloudProvider.AUTH_QUERY));

        CloudProvider.Config odd = new CloudProvider.Config(
            "openai", "https://api.example.com/v1", "abc", "m", "header", "X-Token", null);
        check("a custom header name survives", odd.authName.equals("X-Token"));
        check("a custom auth style survives", odd.authStyle.equals("header"));
    }

    private static void conversation() {
        JSONArray turns = new JSONArray();
        turns.put(turn("user", "one"));
        turns.put(turn("user", "two"));
        turns.put(turn("assistant", "three"));
        turns.put(turn("user", "four"));
        JSONArray merged = CloudProvider.mergeSameRole(turns);
        check("two user turns in a row become one", merged.length() == 3);
        check("their text is joined",
            merged.optJSONObject(0).optString("content").equals("one\n\ntwo"));
        check("the assistant turn is untouched",
            merged.optJSONObject(1).optString("content").equals("three"));
    }

    private static void words() {
        CloudProvider.Config config = new CloudProvider.Config(
            "anthropic", "https://api.anthropic.com/v1", "sk-ant", "claude-3-5-haiku",
            "", "", null);
        check("401 talks about the key",
            CloudProvider.explain(config, 401, "{}").toLowerCase().contains("key"));
        check("404 names the model",
            CloudProvider.explain(config, 404, "{}").contains("claude-3-5-haiku"));
        check("429 says to wait",
            CloudProvider.explain(config, 429, "{}").toLowerCase().contains("again"));
        check("503 blames the provider",
            CloudProvider.explain(config, 503, "{}").contains("their end")
                || CloudProvider.explain(config, 503, "{}").contains("its end"));
        check("the provider's own words are used",
            CloudProvider.explain(config, 400, "{\"error\":{\"message\":\"bad tool block\"}}")
                .contains("bad tool block"));
        check("a plain-text failure still reads",
            CloudProvider.explain(config, 418, "teapot").contains("teapot"));
    }

    private static void greetings() {
        check("hi is not a job", SmallTalk.matches("hi"));
        check("Hello! is not a job", SmallTalk.matches("Hello!"));
        check("hey luna is not a job", SmallTalk.matches("hey luna"));
        check("thanks is not a job", SmallTalk.matches("thanks"));
        check("who are you is not a job", SmallTalk.matches("who are you?"));
        check("good morning is not a job", SmallTalk.matches("good morning"));
        check("read my notes is a job", !SmallTalk.matches("read my notes"));
        check("hi, read notes.md is a job", !SmallTalk.matches("hi, read notes.md"));
        check("open bbc.com is a job", !SmallTalk.matches("open bbc.com"));
        check("a long sentence is a job",
            !SmallTalk.matches("hello can you look through this folder and tell me what is in it"));
        check("an empty message is not small talk", !SmallTalk.matches("   "));
        check("summarise it is a job", !SmallTalk.matches("summarise it"));
    }

    private static void invented() {
        check("example.com is refused",
            NetworkTargets.placeholderReason("https://www.example.com") != null);
        check("example.org is refused",
            NetworkTargets.placeholderReason("http://example.org/page") != null);
        check("yoursite.com is refused",
            NetworkTargets.placeholderReason("https://yoursite.com") != null);
        check("a real site is allowed",
            NetworkTargets.placeholderReason("https://en.wikipedia.org/wiki/Lagos") == null);
        check("no address at all is refused",
            NetworkTargets.placeholderReason("") != null);
        check("the refusal names the placeholder",
            NetworkTargets.placeholderReason("https://example.com").contains("example.com"));
    }

    private static void gate() {
        check("a key file is sensitive", ToolPolicy.isSensitive("keys/id_rsa"));
        check("an env file is sensitive", ToolPolicy.isSensitive("app/.env"));
        check("a keystore is sensitive", ToolPolicy.isSensitive("upload.keystore"));
        check("anything called password is sensitive",
            ToolPolicy.isSensitive("notes/passwords.txt"));
        check("an ordinary note is not", !ToolPolicy.isSensitive("notes/shopping.md"));
        check("an empty path is not", !ToolPolicy.isSensitive(""));
        check("writing changes things", ToolPolicy.isMutating("write_file"));
        check("deleting changes things", ToolPolicy.isMutating("delete_file"));
        check("opening a page leaves the device", ToolPolicy.isMutating("open_page"));
        check("listing a folder does not", !ToolPolicy.isMutating("list_files"));
    }

    private static JSONObject turn(String role, String content) {
        JSONObject out = new JSONObject();
        try {
            out.put("role", role);
            out.put("content", content);
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

    private ProviderConfigTest() {
    }
}
