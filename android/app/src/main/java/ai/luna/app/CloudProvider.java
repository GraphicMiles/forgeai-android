package ai.luna.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Every provider Luna can talk to, on one road.
 *
 * <p>There are exactly three wire shapes in the world worth supporting, and a
 * key is only worth having if the app speaks its shape:
 *
 * <ul>
 *   <li><b>openai</b> — POST /chat/completions with a Bearer key. OpenAI,
 *       Groq, OpenRouter, Together, DeepSeek, Mistral, xAI, Ollama, LM Studio,
 *       llama.cpp's own server, and anything else that copied the format.</li>
 *   <li><b>anthropic</b> — POST /messages with an x-api-key header, an
 *       anthropic-version header, and the system prompt as its own field.</li>
 *   <li><b>gemini</b> — POST /models/{model}:streamGenerateContent with the key
 *       in the query string and the prompt under "contents".</li>
 * </ul>
 *
 * <p>Nothing else about a provider is baked in. The address, the model, the way
 * the key is attached and any extra headers all come from the person adding it,
 * so a provider nobody has heard of yet works on the day it launches — and a
 * model id is never hardcoded, because that is the first thing a provider
 * retires.
 *
 * <p>Answers stream in every shape. A cloud reply that lands in one lump at the
 * end makes a fast provider feel identical to a stalled one.
 */
public final class CloudProvider {

    /** The three shapes. */
    public static final String OPENAI = "openai";
    public static final String ANTHROPIC = "anthropic";
    public static final String GEMINI = "gemini";

    /** How the key is attached. */
    public static final String AUTH_BEARER = "bearer";
    public static final String AUTH_HEADER = "header";
    public static final String AUTH_QUERY = "query";
    public static final String AUTH_NONE = "none";

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** A configured endpoint: a hosted key, or a server on your own network. */
    public static final class Config {
        public final String kind;
        public final String baseUrl;
        public final String apiKey;
        public final String model;

        /** bearer | header | query | none. */
        public final String authStyle;

        /** The header name, or the query parameter name. */
        public final String authName;

        /** Anything else the provider insists on, name to value. */
        public final JSONObject headers;

        public Config(String baseUrl, String apiKey, String model) {
            this(OPENAI, baseUrl, apiKey, model, "", "", null);
        }

        public Config(String kind, String baseUrl, String apiKey, String model,
                      String authStyle, String authName, JSONObject headers) {
            this.kind = normaliseKind(kind);
            this.baseUrl = EndpointPolicy.tidy(baseUrl);
            this.apiKey = apiKey == null ? "" : apiKey.trim();
            // Only Gemini wants a bare id: it lists "models/gemini-2.0-flash"
            // and the request path wants the tail. Everywhere else the part
            // before the slash IS the id — Groq serves "openai/gpt-oss-120b"
            // and "groq/compound-mini", and sending the tail alone is a 404
            // that reads like the app is broken.
            String wanted = model == null ? "" : model.trim();
            this.model = GEMINI.equals(this.kind) ? ModelCatalog.stripPrefix(wanted) : wanted;
            String style = authStyle == null ? "" : authStyle.trim().toLowerCase(Locale.ROOT);
            this.authStyle = style.isEmpty() ? defaultAuthStyle(this.kind) : style;
            String name = authName == null ? "" : authName.trim();
            this.authName = name.isEmpty() ? defaultAuthName(this.kind, this.authStyle) : name;
            this.headers = headers == null ? new JSONObject() : headers;
        }

        /** A copy of this configuration pointed at a different model. */
        public Config withModel(String other) {
            return new Config(kind, baseUrl, apiKey, other, authStyle, authName, headers);
        }

        /** Why this cannot be used yet, in words, or null when it can. */
        public String problem(boolean needsModel) {
            String address = EndpointPolicy.reason(baseUrl);
            if (address != null) {
                return address;
            }
            if (!AUTH_NONE.equals(authStyle) && apiKey.isEmpty()
                && !EndpointPolicy.isPrivateHost(hostOf(baseUrl))) {
                return "This provider needs a key.";
            }
            if (needsModel && model.isEmpty()) {
                return "No model is chosen. Check the list and pick one.";
            }
            return null;
        }
    }

    /** Called for each piece of the answer as it arrives. */
    public interface TokenSink {
        void onToken(String text);
    }

    /** Asked between frames. Stop has to reach a cloud call too. */
    public interface Cancellation {
        boolean cancelled();
    }

    public static final class Reply {
        public final String text;
        public final String error;

        Reply(String text, String error) {
            this.text = text;
            this.error = error;
        }
    }

    private CloudProvider() {
    }

    /** One of the three shapes, whatever was written in the preferences file. */
    public static String normaliseKind(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals(ANTHROPIC) || value.equals("claude")) {
            return ANTHROPIC;
        }
        if (value.equals(GEMINI) || value.equals("google")) {
            return GEMINI;
        }
        return OPENAI;
    }

    /** What a shape uses when nobody says otherwise. */
    public static String defaultAuthStyle(String kind) {
        String shape = normaliseKind(kind);
        if (shape.equals(ANTHROPIC)) {
            return AUTH_HEADER;
        }
        if (shape.equals(GEMINI)) {
            return AUTH_QUERY;
        }
        return AUTH_BEARER;
    }

    /** The header or parameter the key rides in. */
    public static String defaultAuthName(String kind, String style) {
        if (AUTH_QUERY.equals(style)) {
            return "key";
        }
        if (AUTH_HEADER.equals(style)) {
            return ANTHROPIC.equals(normaliseKind(kind)) ? "x-api-key" : "Authorization";
        }
        return "Authorization";
    }

    // --- chat -------------------------------------------------------------------

    /**
     * Streaming chat, in whichever shape this provider speaks. Falls back to a
     * single request when the provider will not stream, so a provider that
     * ignores the flag still answers.
     */
    public static Reply chatStreaming(Config config, List<JSONObject> messages, int maxTokens,
                                      TokenSink sink, Cancellation cancellation) {
        String problem = config.problem(true);
        if (problem != null) {
            return new Reply("", problem);
        }
        try {
            Wire wire = wireFor(config, messages, maxTokens, true);
            if (wire.body.optJSONArray("messages") != null
                && wire.body.optJSONArray("messages").length() == 0) {
                return new Reply("", "There was nothing to send.");
            }
            if (wire.body.optJSONArray("contents") != null
                && wire.body.optJSONArray("contents").length() == 0) {
                return new Reply("", "There was nothing to send.");
            }
            HttpURLConnection connection = open(config, wire.url, true);
            return readStream(config, connection, wire.body, sink, cancellation,
                framesFor(config), wholeFor(config));
        } catch (Exception error) {
            return new Reply("", "Could not reach the provider: " + message(error));
        }
    }

    /**
     * Is this model actually usable by this key, right now?
     *
     * <p>Being in the /models list is not the same as being usable: a provider
     * will happily list a model your key has no entitlement for, and the first
     * you hear of it is a 404 in the middle of a job. So nothing is saved on
     * the strength of a listing — one real request is sent, asking for a single
     * token, and the answer to that is the truth.
     */
    public static String probe(Config config) {
        String problem = config.problem(true);
        if (problem != null) {
            return problem;
        }
        HttpURLConnection connection = null;
        try {
            List<JSONObject> messages = new ArrayList<>();
            JSONObject hello = new JSONObject();
            hello.put("role", "user");
            hello.put("content", "Hi");
            messages.add(hello);

            Wire wire = wireFor(config, messages, 1, false);
            connection = open(config, wire.url, true);
            connection.setReadTimeout(30000);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(wire.body.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally {
                WorkspaceStore.closeQuietly(output);
            }
            int code = connection.getResponseCode();
            if (code >= 200 && code < 300) {
                ErrorLog.tapNote("probe", code + " " + config.model + " answers");
                return null;
            }
            String failed = readAll(connection.getErrorStream());
            ErrorLog.tapFail("probe", code + " " + config.model + " " + trim(failed, 300));
            return explain(config, code, failed);
        } catch (Exception error) {
            ErrorLog.tapFail("probe", String.valueOf(error));
            return "Could not reach the provider: " + message(error);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Where a request goes and what it carries. Built once, used by both the
     * real run and the availability probe, so the two can never disagree. */
    private static final class Wire {
        final String url;
        final JSONObject body;

        Wire(String url, JSONObject body) {
            this.url = url;
            this.body = body;
        }
    }

    private static Wire wireFor(Config config, List<JSONObject> messages, int maxTokens,
                                boolean stream) throws Exception {
        if (ANTHROPIC.equals(config.kind)) {
            return anthropicWire(config, messages, maxTokens, stream);
        }
        if (GEMINI.equals(config.kind)) {
            return geminiWire(config, messages, maxTokens, stream);
        }
        return openAiWire(config, messages, maxTokens, stream);
    }

    private static Wire openAiWire(Config config, List<JSONObject> messages, int maxTokens,
                                   boolean stream) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", config.model);
        body.put("stream", stream);
        body.put("max_tokens", maxTokens);
        // GPT-OSS models think into a dedicated reasoning field that Groq
        // includes by default. On a reasoning-heavy turn that thinking can
        // spend the whole token budget before any answer lands in "content",
        // so the stream closes with no answer and reads like the provider
        // died. Asking for the answer only keeps the budget for the answer.
        if (isReasoningModel(config.model)) {
            body.put("include_reasoning", false);
        }
        JSONArray array = new JSONArray();
        for (JSONObject message : messages) {
            array.put(message);
        }
        body.put("messages", array);
        return new Wire(config.baseUrl + "/chat/completions", body);
    }

    /** Models whose thinking arrives in a separate reasoning field. */
    private static boolean isReasoningModel(String model) {
        String id = model == null ? "" : model.toLowerCase(Locale.US);
        return id.contains("gpt-oss");
    }

    private static Wire anthropicWire(Config config, List<JSONObject> messages, int maxTokens,
                                      boolean stream) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", config.model);
        body.put("stream", stream);
        // Anthropic requires it, and will not guess a sensible one for you.
        body.put("max_tokens", maxTokens <= 0 ? 1024 : maxTokens);

        StringBuilder system = new StringBuilder();
        JSONArray turns = new JSONArray();
        for (JSONObject message : messages) {
            String role = message.optString("role", "user");
            String content = message.optString("content", "");
            if (content.isEmpty()) {
                continue;
            }
            if (role.equals("system")) {
                if (system.length() > 0) {
                    system.append("\n\n");
                }
                system.append(content);
                continue;
            }
            JSONObject turn = new JSONObject();
            turn.put("role", role.equals("assistant") ? "assistant" : "user");
            turn.put("content", content);
            turns.put(turn);
        }
        if (system.length() > 0) {
            body.put("system", system.toString());
        }
        body.put("messages", mergeSameRole(turns));
        return new Wire(config.baseUrl + "/messages", body);
    }

    private static Wire geminiWire(Config config, List<JSONObject> messages, int maxTokens,
                                   boolean stream) throws Exception {
        JSONObject body = new JSONObject();
        StringBuilder system = new StringBuilder();
        JSONArray contents = new JSONArray();
        for (JSONObject message : messages) {
            String role = message.optString("role", "user");
            String content = message.optString("content", "");
            if (content.isEmpty()) {
                continue;
            }
            if (role.equals("system")) {
                if (system.length() > 0) {
                    system.append("\n\n");
                }
                system.append(content);
                continue;
            }
            JSONObject turn = new JSONObject();
            turn.put("role", role.equals("assistant") ? "model" : "user");
            turn.put("parts", new JSONArray().put(new JSONObject().put("text", content)));
            contents.put(turn);
        }
        body.put("contents", contents);
        if (system.length() > 0) {
            JSONObject instruction = new JSONObject();
            instruction.put("parts", new JSONArray().put(new JSONObject().put("text", system.toString())));
            body.put("systemInstruction", instruction);
        }
        JSONObject generation = new JSONObject();
        generation.put("maxOutputTokens", maxTokens <= 0 ? 1024 : maxTokens);
        body.put("generationConfig", generation);

        String action = stream ? ":streamGenerateContent?alt=sse" : ":generateContent";
        return new Wire(config.baseUrl + "/models/" + encode(config.model) + action, body);
    }

    /** How to read one streamed frame, per shape. */
    private static FrameReader framesFor(Config config) {
        if (ANTHROPIC.equals(config.kind)) {
            return new FrameReader() {
                @Override
                public String textOf(JSONObject frame) {
                    String type = frame.optString("type", "");
                    if (type.equals("content_block_delta")) {
                        JSONObject delta = frame.optJSONObject("delta");
                        return delta == null ? "" : delta.optString("text", "");
                    }
                    if (type.equals("content_block_start")) {
                        JSONObject block = frame.optJSONObject("content_block");
                        return block == null ? "" : block.optString("text", "");
                    }
                    return "";
                }
            };
        }
        if (GEMINI.equals(config.kind)) {
            return new FrameReader() {
                @Override
                public String textOf(JSONObject frame) {
                    return geminiText(frame);
                }
            };
        }
        return new FrameReader() {
            @Override
            public String textOf(JSONObject frame) {
                JSONArray choices = frame.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    return "";
                }
                JSONObject choice = choices.optJSONObject(0);
                if (choice == null) {
                    return "";
                }
                JSONObject delta = choice.optJSONObject("delta");
                if (delta != null) {
                    String content = delta.optString("content", "");
                    if (!content.isEmpty()) {
                        return content;
                    }
                    // Reasoning models stream their thinking into their own
                    // field while content stays empty. Taking it beats failing
                    // with "streamed nothing back"; the real answer, when the
                    // model produces one, still arrives on top of it.
                    String reasoning = delta.optString("reasoning", "");
                    if (!reasoning.isEmpty()) {
                        return reasoning;
                    }
                    reasoning = delta.optString("reasoning_content", "");
                    if (!reasoning.isEmpty()) {
                        return reasoning;
                    }
                    return "";
                }
                // Some servers stream the non-delta shape.
                JSONObject whole = choice.optJSONObject("message");
                if (whole == null) {
                    return choice.optString("text", "");
                }
                String content = whole.optString("content", "");
                return content.isEmpty() ? whole.optString("reasoning", "") : content;
            }
        };
    }

    /** How to read a whole non-streamed document, per shape. */
    private static WholeReader wholeFor(Config config) {
        if (ANTHROPIC.equals(config.kind)) {
            return new WholeReader() {
                @Override
                public String textOf(JSONObject document) {
                    JSONArray content = document.optJSONArray("content");
                    if (content == null) {
                        return "";
                    }
                    StringBuilder out = new StringBuilder();
                    for (int index = 0; index < content.length(); index++) {
                        JSONObject block = content.optJSONObject(index);
                        if (block != null) {
                            out.append(block.optString("text", ""));
                        }
                    }
                    return out.toString();
                }
            };
        }
        if (GEMINI.equals(config.kind)) {
            return new WholeReader() {
                @Override
                public String textOf(JSONObject document) {
                    return geminiText(document);
                }
            };
        }
        return new WholeReader() {
            @Override
            public String textOf(JSONObject document) {
                return firstChoice(document);
            }
        };
    }

    private static String geminiText(JSONObject document) {
        JSONArray candidates = document.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            return "";
        }
        JSONObject content = candidates.optJSONObject(0) == null
            ? null : candidates.optJSONObject(0).optJSONObject("content");
        if (content == null) {
            return "";
        }
        JSONArray parts = content.optJSONArray("parts");
        if (parts == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < parts.length(); index++) {
            JSONObject part = parts.optJSONObject(index);
            if (part != null) {
                out.append(part.optString("text", ""));
            }
        }
        return out.toString();
    }

    /** Two consecutive user turns are illegal for Anthropic; join them. */
    static JSONArray mergeSameRole(JSONArray turns) {
        JSONArray out = new JSONArray();
        try {
            for (int index = 0; index < turns.length(); index++) {
                JSONObject turn = turns.optJSONObject(index);
                if (turn == null) {
                    continue;
                }
                JSONObject previous = out.length() == 0 ? null : out.optJSONObject(out.length() - 1);
                if (previous != null
                    && previous.optString("role").equals(turn.optString("role"))) {
                    previous.put("content",
                        previous.optString("content") + "\n\n" + turn.optString("content"));
                    continue;
                }
                out.put(turn);
            }
        } catch (Exception badTurn) {
            return turns;
        }
        return out;
    }

    // --- the one road ------------------------------------------------------------

    private interface FrameReader {
        String textOf(JSONObject frame);
    }

    private interface WholeReader {
        String textOf(JSONObject document);
    }

    /**
     * Writes the body, then reads whatever comes back — server-sent events when
     * the provider streams, one document when it does not. The difference is
     * invisible above this method.
     */
    private static Reply readStream(Config config, HttpURLConnection connection, JSONObject body,
                                    TokenSink sink, Cancellation cancellation,
                                    FrameReader frames, WholeReader whole) {
        BufferedReader reader = null;
        try {
            OutputStream output = connection.getOutputStream();
            try {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally {
                WorkspaceStore.closeQuietly(output);
            }

            int code = connection.getResponseCode();
            if (code >= 400) {
                String body2 = readAll(connection.getErrorStream());
                ErrorLog.tapFail("http", "chat " + code + " " + trim(body2, 300));
                return new Reply("", explain(config, code, body2));
            }
            ErrorLog.tapNote("http", "chat " + code + " streaming");

            String contentType = connection.getContentType();
            if (contentType != null && !contentType.contains("event-stream")) {
                // The provider ignored the flag and sent one JSON document.
                String payload = readAll(connection.getInputStream());
                String text = "";
                try {
                    text = whole.textOf(new JSONObject(payload));
                } catch (Exception notJson) {
                    return new Reply("", "The provider sent something that was not an answer: "
                        + trim(payload, 200));
                }
                if (!text.isEmpty()) {
                    sink.onToken(text);
                }
                return text.isEmpty()
                    ? new Reply("", "The provider sent an empty answer.")
                    : new Reply(text, null);
            }

            StringBuilder gathered = new StringBuilder();
            reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancellation != null && cancellation.cancelled()) {
                    // Whatever arrived is kept; the rest is abandoned.
                    return new Reply(gathered.toString(), null);
                }
                if (!line.startsWith("data:")) {
                    // "event:" lines and blank separators carry no text.
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if (data.equals("[DONE]")) {
                    break;
                }
                try {
                    JSONObject frame = new JSONObject(data);
                    String error = errorOf(frame);
                    if (error != null) {
                        // The provider said why it failed, inside a 200 stream.
                        // Saying "streamed nothing back" would hide the one
                        // thing worth knowing.
                        ErrorLog.tapFail("http", "chat stream error: " + trim(error, 300));
                        return new Reply("", error);
                    }
                    String piece = frames.textOf(frame);
                    if (!piece.isEmpty()) {
                        gathered.append(piece);
                        sink.onToken(piece);
                    } else if (frame.optJSONArray("choices") == null) {
                        // A normal first frame carries an empty choices delta;
                        // a frame with no choices at all is something unusual,
                        // so keep it to make the next empty stream diagnosable.
                        ErrorLog.tapNote("http", "frame with no choices: " + trim(data, 200));
                    }
                } catch (Exception badFrame) {
                    // One malformed frame does not end the answer.
                }
            }
            if (gathered.length() == 0) {
                return new Reply("", "The provider streamed nothing back.");
            }
            return new Reply(gathered.toString(), null);
        } catch (Exception error) {
            ErrorLog.tapFail("http", String.valueOf(error));
            return new Reply("", "Could not reach the provider: " + message(error));
        } finally {
            WorkspaceStore.closeQuietly(reader);
            connection.disconnect();
        }
    }

    /** Opens a connection with the key attached the way this provider wants it. */
    private static HttpURLConnection open(Config config, String url, boolean post) throws Exception {
        String address = url;
        if (AUTH_QUERY.equals(config.authStyle) && !config.apiKey.isEmpty()) {
            address += (address.indexOf('?') >= 0 ? "&" : "?")
                + encode(config.authName) + "=" + encode(config.apiKey);
        }
        ErrorLog.tapNote("http", (post ? "POST " : "GET ") + ErrorLog.safeUrl(address)
            + (config.model.isEmpty() ? "" : "  model=" + config.model)
            + "  shape=" + config.kind);
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod(post ? "POST" : "GET");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(180000);
        connection.setRequestProperty("Accept", post ? "text/event-stream" : "application/json");
        if (post) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
        }
        if (!config.apiKey.isEmpty()) {
            if (AUTH_BEARER.equals(config.authStyle)) {
                connection.setRequestProperty(config.authName, "Bearer " + config.apiKey);
            } else if (AUTH_HEADER.equals(config.authStyle)) {
                connection.setRequestProperty(config.authName, config.apiKey);
            }
        }
        if (ANTHROPIC.equals(config.kind) && !config.headers.has("anthropic-version")) {
            connection.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
        }
        Iterator<String> names = config.headers.keys();
        while (names.hasNext()) {
            String name = names.next();
            String value = config.headers.optString(name, "");
            if (!name.trim().isEmpty() && !value.isEmpty()) {
                connection.setRequestProperty(name.trim(), value);
            }
        }
        return connection;
    }

    // --- models ------------------------------------------------------------------

    /**
     * What this provider says it serves today, chat models first.
     *
     * <p>Asked rather than baked in: a list inside the app goes stale the
     * moment a provider retires a model, and the 404 that follows reads like a
     * bug in Luna.
     */
    public static JSONArray listModels(Config config) throws Exception {
        String problem = config.problem(false);
        if (problem != null) {
            throw new IllegalStateException(problem);
        }
        HttpURLConnection connection = open(config, config.baseUrl + "/models", false);
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException(
                    explain(config, code, readAll(connection.getErrorStream())));
            }
            JSONObject json = new JSONObject(readAll(connection.getInputStream()));
            List<String> ids = new ArrayList<>();
            if (GEMINI.equals(config.kind)) {
                JSONArray models = json.optJSONArray("models");
                for (int index = 0; models != null && index < models.length(); index++) {
                    JSONObject item = models.optJSONObject(index);
                    if (item == null || !generatesContent(item)) {
                        continue;
                    }
                    String name = ModelCatalog.stripPrefix(item.optString("name", ""));
                    if (!name.isEmpty()) {
                        ids.add(name);
                    }
                }
            } else {
                JSONArray data = json.optJSONArray("data");
                if (data == null) {
                    data = json.optJSONArray("models");
                }
                for (int index = 0; data != null && index < data.length(); index++) {
                    JSONObject item = data.optJSONObject(index);
                    String id = item == null
                        ? data.optString(index, "")
                        : item.optString("id", item.optString("name", ""));
                    if (id != null && !id.isEmpty()) {
                        ids.add(id);
                    }
                }
            }
            return ModelCatalog.orderedArray(ids);
        } finally {
            connection.disconnect();
        }
    }

    /** Gemini lists embedding and media models beside the ones that answer. */
    private static boolean generatesContent(JSONObject model) {
        JSONArray methods = model.optJSONArray("supportedGenerationMethods");
        if (methods == null) {
            return true;
        }
        for (int index = 0; index < methods.length(); index++) {
            if ("generateContent".equals(methods.optString(index))) {
                return true;
            }
        }
        return false;
    }

    /** Ask an Ollama server which models it is serving. */
    public static JSONArray ollamaModels(String endpoint) {
        HttpURLConnection connection = null;
        try {
            String base = EndpointPolicy.tidy(endpoint);
            connection = (HttpURLConnection) new URL(base + "/api/tags").openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(6000);
            if (connection.getResponseCode() != 200) {
                return new JSONArray();
            }
            JSONObject json = new JSONObject(readAll(connection.getInputStream()));
            JSONArray models = json.optJSONArray("models");
            return models == null ? new JSONArray() : models;
        } catch (Exception error) {
            return new JSONArray();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // --- words -------------------------------------------------------------------

    /**
     * Someone else's HTTP code, turned into a sentence about what to do. A bare
     * "401" tells you nothing; "the key was rejected" tells you where to look.
     */
    static String explain(Config config, int code, String payload) {
        String detail = detailOf(payload);
        String where = label(config.kind);
        switch (code) {
            case 400:
                return where + " rejected the request: " + detail;
            case 401:
                return "The key was rejected by " + where + ". Check it, or paste a new one.";
            case 403:
                return where + " refused this key (403). It may not have access to " 
                    + (config.model.isEmpty() ? "that model" : "\"" + config.model + "\"") + ".";
            case 404:
                return config.model.isEmpty()
                    ? "That address does not exist on " + where + ". Check the base address."
                    : "\"" + config.model + "\" is not available on this key. Check the model list "
                        + "and pick another.";
            case 413:
                return "The conversation is too long for " + where + ". Start a new chat.";
            case 429:
                return where + " is rate-limiting this key. Wait a moment and try again.";
            case 500:
            case 502:
            case 503:
            case 504:
                return where + " is having trouble at its end (" + code + "). Try again shortly.";
            default:
                return where + " answered " + code + ": " + detail;
        }
    }

    private static String label(String kind) {
        if (ANTHROPIC.equals(kind)) {
            return "Anthropic";
        }
        if (GEMINI.equals(kind)) {
            return "Google";
        }
        return "The provider";
    }

    /** The provider's own explanation, dug out of whichever envelope it used. */
    private static String detailOf(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            JSONObject error = json.optJSONObject("error");
            if (error != null) {
                String message = error.optString("message", "");
                if (!message.isEmpty()) {
                    return trim(message, 200);
                }
            }
            String message = json.optString("message", "");
            if (!message.isEmpty()) {
                return trim(message, 200);
            }
        } catch (Exception notJson) {
            // Fall through to the raw text.
        }
        return trim(payload, 200);
    }

    /** The provider's own error, out of whichever envelope it used, or null. */
    private static String errorOf(JSONObject frame) {
        JSONObject error = frame.optJSONObject("error");
        if (error == null) {
            return null;
        }
        String message = error.optString("message", "");
        if (!message.isEmpty()) {
            return trim(message, 300);
        }
        return trim(error.toString(), 300);
    }

    private static String firstChoice(JSONObject json) {
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return "";
        }
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) {
            return "";
        }
        JSONObject message = choice.optJSONObject("message");
        if (message == null) {
            return choice.optString("text", "");
        }
        String content = message.optString("content", "");
        return content.isEmpty() ? message.optString("reasoning", "") : content;
    }

    private static String hostOf(String raw) {
        try {
            return new java.net.URI(raw).getHost();
        } catch (Exception malformed) {
            return "";
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception impossible) {
            return value;
        }
    }

    private static String message(Exception error) {
        String text = error.getMessage();
        return text == null || text.isEmpty() ? error.getClass().getSimpleName() : text;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            return out.toString();
        } finally {
            WorkspaceStore.closeQuietly(reader);
        }
    }

    private static String trim(String value, int limit) {
        if (value == null) {
            return "";
        }
        String single = value.replace('\n', ' ').trim();
        return single.length() > limit ? single.substring(0, limit) + "…" : single;
    }
}
