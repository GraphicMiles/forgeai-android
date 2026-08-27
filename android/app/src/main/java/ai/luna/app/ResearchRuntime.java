package ai.luna.app;

import android.util.Xml;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

@CapacitorPlugin(name = "ResearchRuntime")
public class ResearchRuntime extends Plugin {
    private static final int MAX_RESPONSE_CHARS = 750_000;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private void validatePublicHttps(String rawUrl) throws Exception {
        URI uri = URI.create(rawUrl);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) throw new IllegalArgumentException("Only public HTTPS URLs are allowed.");
        for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException("Private, local, and multicast network targets are blocked.");
            }
        }
    }

    private String requestText(String url, String accept) throws Exception {
        validatePublicHttps(url);
        HttpURLConnection connection = (HttpURLConnection) new java.net.URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "Luna-Android/1.0");
        if (accept != null) connection.setRequestProperty("Accept", accept);
        int code = connection.getResponseCode();
        if (!"https".equalsIgnoreCase(connection.getURL().getProtocol())) throw new IllegalArgumentException("Research request redirected to a non-HTTPS URL.");
        if (code < 200 || code >= 300) throw new IllegalArgumentException("Research request failed with HTTP " + code + ".");
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                if (output.length() + count > MAX_RESPONSE_CHARS) throw new IllegalArgumentException("Research response exceeded the output limit.");
                output.append(buffer, 0, count);
            }
        } finally { connection.disconnect(); }
        return output.toString();
    }

    private JSArray googleSearch(String query, String key, String cx) throws Exception {
        String url = "https://www.googleapis.com/customsearch/v1?key=" + URLEncoder.encode(key, "UTF-8")
            + "&cx=" + URLEncoder.encode(cx, "UTF-8") + "&q=" + URLEncoder.encode(query, "UTF-8") + "&num=8";
        JSONObject response = new JSONObject(requestText(url, "application/json"));
        JSONArray items = response.optJSONArray("items");
        JSArray result = new JSArray();
        if (items != null) for (int index = 0; index < items.length(); index++) {
            JSONObject source = items.getJSONObject(index);
            JSObject item = new JSObject();
            item.put("title", source.optString("title"));
            item.put("url", source.optString("link"));
            item.put("snippet", source.optString("snippet"));
            item.put("source", "Google Programmable Search");
            result.put(item);
        }
        return result;
    }

    private JSArray wikipediaSearch(String query) throws Exception {
        String url = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&utf8=1&srlimit=5&srsearch=" + URLEncoder.encode(query, "UTF-8");
        JSONObject response = new JSONObject(requestText(url, "application/json"));
        JSONArray items = response.getJSONObject("query").getJSONArray("search");
        JSArray result = new JSArray();
        for (int index = 0; index < items.length(); index++) {
            JSONObject source = items.getJSONObject(index);
            String title = source.optString("title");
            JSObject item = new JSObject();
            item.put("title", title);
            item.put("url", "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"));
            item.put("snippet", source.optString("snippet").replaceAll("<[^>]+>", ""));
            item.put("source", "Wikipedia");
            result.put(item);
        }
        return result;
    }

    private JSArray newsSearch(String query) throws Exception {
        String url = "https://news.google.com/rss/search?q=" + URLEncoder.encode(query, "UTF-8") + "&hl=en&gl=US&ceid=US:en";
        validatePublicHttps(url);
        HttpURLConnection connection = (HttpURLConnection) new java.net.URL(url).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(30000); connection.setRequestProperty("User-Agent", "Luna-Android/1.0");
        if (connection.getResponseCode() != 200) throw new IllegalArgumentException("Google News RSS failed.");
        JSArray result = new JSArray();
        try (InputStream input = connection.getInputStream()) {
            XmlPullParser parser = Xml.newPullParser(); parser.setInput(input, "UTF-8");
            boolean inItem = false; String title = "", link = "", description = "";
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT && result.length() < 6) {
                if (event == XmlPullParser.START_TAG && "item".equals(parser.getName())) { inItem = true; title = link = description = ""; }
                else if (event == XmlPullParser.END_TAG && "item".equals(parser.getName())) {
                    inItem = false; JSObject item = new JSObject(); item.put("title", title); item.put("url", link); item.put("snippet", description.replaceAll("<[^>]+>", "")); item.put("source", "Google News RSS"); result.put(item);
                } else if (inItem && event == XmlPullParser.START_TAG) {
                    String name = parser.getName();
                    if ("title".equals(name)) title = parser.nextText();
                    else if ("link".equals(name)) link = parser.nextText();
                    else if ("description".equals(name)) description = parser.nextText();
                }
                event = parser.next();
            }
        } finally { connection.disconnect(); }
        return result;
    }

    @PluginMethod
    public void search(PluginCall call) {
        String query = call.getString("query", "").trim();
        if (query.isEmpty()) { call.reject("A research query is required."); return; }
        executor.execute(() -> {
            try {
                String key = call.getString("googleApiKey", "");
                String cx = call.getString("googleCx", "");
                JSArray items;
                String provider;
                if (!key.isEmpty() && !cx.isEmpty()) { items = googleSearch(query, key, cx); provider = "google"; }
                else {
                    items = wikipediaSearch(query);
                    JSArray news = newsSearch(query);
                    for (int index = 0; index < news.length(); index++) items.put(news.get(index));
                    provider = "public-fallback";
                }
                JSObject result = new JSObject(); result.put("query", query); result.put("provider", provider); result.put("items", items); result.put("searchedAt", System.currentTimeMillis()); call.resolve(result);
            } catch (Exception error) { call.reject(error.getMessage(), "RESEARCH_FAILED"); }
        });
    }

    @PluginMethod
    public void fetchUrl(PluginCall call) {
        String url = call.getString("url", "").trim();
        executor.execute(() -> {
            try {
                String text = requestText(url, "text/html,application/json,text/plain,application/xml");
                JSObject result = new JSObject(); result.put("url", url); result.put("content", text); result.put("fetchedAt", System.currentTimeMillis()); call.resolve(result);
            } catch (Exception error) { call.reject(error.getMessage(), "FETCH_FAILED"); }
        });
    }
}
