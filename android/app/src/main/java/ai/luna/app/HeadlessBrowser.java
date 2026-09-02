package ai.luna.app;

import ai.luna.contracts.BrowserProvider;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A browser with no window.
 *
 * The system WebView is already on the phone, so this costs nothing in the APK.
 * It is created off-screen, never attached to a layout, and thrown away at the
 * end of a job along with its cookies.
 *
 * Two rules make it safe enough to hand to a model. Requests for images, fonts,
 * media and anything that is not the page itself are refused before they leave,
 * so a page cannot be used to fetch a payload. And every load has a timeout: a
 * page that never settles fails instead of hanging the job forever.
 */
public final class HeadlessBrowser implements BrowserProvider {

    /** The first browser provider: the system WebView, with no window. */
    @Override
    public String id() {
        return "android.webview";
    }


    private static final long DEFAULT_TIMEOUT_MS = 20000L;
    private static final int MAX_TEXT = 40000;

    /** A Google search page is scraped this many times before giving up. */
    private static final int SEARCH_SCRAPE_TRIES = 3;

    /** How long the page is left to settle between those scrapes. */
    private static final long SEARCH_SETTLE_MS = 1200L;

    /**
     * Pulls Google's result blocks into one "title || address || snippet" line
     * each. Google wraps the real address in a /url?q= redirect and sprinkles
     * the page with non-result links, so the script only keeps blocks that
     * have a title, resolves the address, and drops repeats.
     */
    private static final String SEARCH_EXTRACT =
        "(function(){"
            + "var out=[], seen={};"
            + "function clean(s){ return (s||'').replace(/\\s+/g,' ').trim(); }"
            + "function decode(u){"
            + "try{"
            + "var m=u.match(/[?&](?:q|url)=([^&]+)/i);"
            + "if(m){ return decodeURIComponent(m[1]); }"
            + "}catch(e){}"
            + "return u;"
            + "}"
            + "var blocks=document.querySelectorAll('div.g, div.Gx5Zad');"
            + "for(var i=0;i<blocks.length&&out.length<10;i++){"
            + "var b=blocks[i];"
            + "var a=b.querySelector('a[href]');"
            + "var h3=b.querySelector('h3');"
            + "var title=clean(h3?h3.innerText:'');"
            + "var href=a?a.getAttribute('href'):'';"
            + "if(!title||!href){continue;}"
            + "href=decode(href);"
            + "if(href.indexOf('http')!==0){continue;}"
            + "if(seen[href]){continue;}"
            + "seen[href]=1;"
            + "var snip=b.querySelector('[data-sncf], .VwiC3b, .IsZvec, .aCOpRe');"
            + "var text=clean(snip?snip.innerText:b.innerText.replace(title,''));"
            + "if(text.length>300){text=text.slice(0,300)+'…';}"
            + "out.push(title+' || '+href+' || '+text);"
            + "}"
            + "if(out.length===0){"
            + "var links=document.querySelectorAll('a[href*=\"/url?q=\"]');"
            + "for(var j=0;j<links.length&&out.length<10;j++){"
            + "var l=links[j];"
            + "var t=clean(l.innerText);"
            + "if(!t){continue;}"
            + "var h=decode(l.getAttribute('href'));"
            + "if(h.indexOf('http')!==0||seen[h]){continue;}"
            + "seen[h]=1;"
            + "out.push(t+' || '+h+' || ');"
            + "}"
            + "}"
            + "return out.join('\\n');"
            + "})();";

    /**
     * Pictures and videos on the page, as addresses rather than bytes.
     *
     * <p>The browser deliberately never downloads an image — it is headless and
     * the bytes would be wasted. But a person who asks for "pictures of" or
     * "the latest video" wants to see something, and Flutter can load a
     * thumbnail perfectly well from a URL. So the scrape collects addresses and
     * the chat does the rendering.
     *
     * <p>Three sources, in order of how much they can be trusted: Google's own
     * image results carry the original address in their metadata, YouTube
     * results have a video id that yields a poster frame, and any page has
     * plain img tags worth keeping if they are big enough to be content rather
     * than an icon.
     */
    private static final String MEDIA_EXTRACT =
        "(function(){"
            + "var out=[], seen={};"
            + "function clean(s){ return (s||'').replace(/\\s+/g,' ').trim(); }"
            + "function push(kind,src,page,title,extra){"
            + "if(!src||src.indexOf('http')!==0||seen[src]){return;}"
            + "if(out.length>=12){return;}"
            + "seen[src]=1;"
            + "out.push(kind+' || '+src+' || '+(page||'')+' || '+clean(title)+' || '+(extra||''));"
            + "}"
            // YouTube results: the video id gives a poster frame and a watch url.
            + "var vids=document.querySelectorAll('a[href*=\"watch?v=\"], a[href*=\"youtu.be/\"]');"
            + "for(var v=0;v<vids.length;v++){"
            + "var vh=vids[v].getAttribute('href')||'';"
            + "var m=vh.match(/(?:watch\\?v=|youtu\\.be\\/)([A-Za-z0-9_-]{11})/);"
            + "if(!m){continue;}"
            + "var id=m[1];"
            + "var vt=clean(vids[v].innerText)||clean(vids[v].getAttribute('title'));"
            + "push('video','https://i.ytimg.com/vi/'+id+'/hqdefault.jpg',"
            + "'https://www.youtube.com/watch?v='+id,vt,id);"
            + "}"
            // Google Images packs the original address into a JSON blob.
            + "var metas=document.querySelectorAll('div[data-lpage], a[href*=\"imgurl=\"]');"
            + "for(var g=0;g<metas.length;g++){"
            + "var gh=metas[g].getAttribute('href')||'';"
            + "var gm=gh.match(/[?&]imgurl=([^&]+)/);"
            + "if(gm){"
            + "try{ push('image',decodeURIComponent(gm[1]),"
            + "metas[g].getAttribute('data-lpage')||'',clean(metas[g].innerText),''); }catch(e){}"
            + "}"
            + "}"
            // Anything else on the page that is plainly a picture, not an icon.
            + "var imgs=document.querySelectorAll('img');"
            + "for(var i=0;i<imgs.length;i++){"
            + "var im=imgs[i];"
            + "var w=im.naturalWidth||im.width||0, h=im.naturalHeight||im.height||0;"
            + "if(w&&w<160){continue;}"
            + "if(h&&h<120){continue;}"
            + "var src=im.getAttribute('src')||im.getAttribute('data-src')||'';"
            + "if(src.indexOf('data:')===0){continue;}"
            + "push('image',src,location.href,im.getAttribute('alt')||'','');"
            + "}"
            + "return out.join('\\n');"
            + "})();";

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ErrorLog errors;

    // Touched from the agent worker and the main thread.
    private volatile WebView web;
    private volatile String currentUrl = "";
    private volatile String currentTitle = "";

    public HeadlessBrowser(Context context, ErrorLog errors) {
        this.context = context.getApplicationContext();
        this.errors = errors;
    }

    public String currentUrl() {
        return currentUrl;
    }

    public String currentTitle() {
        return currentTitle;
    }

    /** What is on screen right now, in reading order. Empty when nothing is open. */
    public String open(String url, long timeoutMs) {
        String verdict = NetworkTargets.checkResolved(url);
        if (verdict != null) {
            return "refused: " + verdict;
        }
        final String target = NetworkTargets.normalise(url);
        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicReference<String> failure = new AtomicReference<>(null);

        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureWebView();
                    web.setWebViewClient(new WebViewClient() {
                        @Override
                        public void onPageFinished(WebView view, String finished) {
                            currentUrl = finished == null ? target : finished;
                            currentTitle = view.getTitle() == null ? "" : view.getTitle();
                            ready.countDown();
                        }

                        @Override
                        public void onReceivedError(WebView view, WebResourceRequest request,
                                                    android.webkit.WebResourceError error) {
                            if (request != null && request.isForMainFrame()) {
                                failure.set("the page did not load");
                                ready.countDown();
                            }
                        }

                        @Override
                        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                            if (request == null) {
                                return null;
                            }
                            String requested = request.getUrl() == null ? "" : request.getUrl().toString();
                            if (!request.isForMainFrame() && isHeavy(requested)) {
                                return empty();
                            }
                            if (NetworkTargets.check(requested) != null) {
                                return empty();
                            }
                            return null;
                        }
                    });
                    seedSearchConsent(target);
                    web.loadUrl(target);
                } catch (Throwable error) {
                    failure.set(String.valueOf(error.getMessage()));
                    ready.countDown();
                }
            }
        });

        try {
            if (!ready.await(timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs, TimeUnit.MILLISECONDS)) {
                // Otherwise the page carries on loading in the background after
                // the tool has already reported failure.
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        WebView open = web;
                        if (open != null) {
                            open.stopLoading();
                        }
                    }
                });
                currentUrl = "";
                currentTitle = "";
                return "refused: the page took too long and was abandoned";
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return "refused: stopped";
        }
        if (failure.get() != null) {
            errors.record("open_page", target + ": " + failure.get());
            currentUrl = "";
            currentTitle = "";
            return "refused: " + failure.get();
        }
        return "";
    }

    /** The readable text of whatever is open. */
    public String text() {
        return ReadableText.clean(evaluate(
            "(function(){var c=document.querySelector('article')||document.body;"
                + "if(!c)return '';var clone=c.cloneNode(true);"
                + "var junk=clone.querySelectorAll('script,style,noscript,nav,footer,header,aside');"
                + "for(var i=0;i<junk.length;i++){junk[i].remove();}"
                + "return clone.innerText;})();"), MAX_TEXT);
    }

    /** The open page's search results, as "title || address || snippet" lines. */
    @Override
    /**
     * Media on the current page. Same settle-and-retry as the result scrape,
     * because a search page paints its thumbnails last of all.
     */
    public String media() {
        for (int attempt = 0; attempt < SEARCH_SCRAPE_TRIES; attempt++) {
            String found = ReadableText.clean(evaluate(MEDIA_EXTRACT), MAX_TEXT);
            if (!found.isEmpty() || attempt == SEARCH_SCRAPE_TRIES - 1) {
                return found;
            }
            try {
                Thread.sleep(SEARCH_SETTLE_MS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return found;
            }
        }
        return "";
    }

    public String searchResults() {
        // Google often paints its result blocks a beat after onPageFinished, or
        // shows a consent wall before them. One empty scrape is not proof there
        // is nothing to read, so the page is left to settle and scraped again.
        for (int attempt = 0; attempt < SEARCH_SCRAPE_TRIES; attempt++) {
            String results = ReadableText.clean(evaluate(SEARCH_EXTRACT), MAX_TEXT);
            if (!results.isEmpty() || attempt == SEARCH_SCRAPE_TRIES - 1) {
                return results;
            }
            try {
                Thread.sleep(SEARCH_SETTLE_MS);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return results;
            }
        }
        return "";
    }

    /** Runs one script on the web view and waits for its answer. */
    private String evaluate(String script) {
        if (web == null) {
            return "";
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> holder = new AtomicReference<>("");
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    WebView view = web;
                    if (view == null) {
                        done.countDown();
                        return;
                    }
                    view.evaluateJavascript(script, value -> {
                        holder.set(value == null ? "" : value);
                        done.countDown();
                    });
                } catch (Throwable error) {
                    done.countDown();
                }
            }
        });
        try {
            done.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
        return holder.get();
    }

    /** Called when a job ends. The next job starts with no history and no cookies. */
    public void close() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (web == null) {
                    return;
                }
                try {
                    web.stopLoading();
                    web.loadUrl("about:blank");
                    web.clearHistory();
                    web.clearCache(true);
                    CookieManager.getInstance().removeAllCookies(null);
                    web.destroy();
                } catch (Throwable ignored) {
                    // Tearing down is best effort.
                } finally {
                    web = null;
                    currentUrl = "";
                    currentTitle = "";
                }
            }
        });
    }

    /**
     * Google sometimes answers a brand-new browser with a consent wall instead
     * of results. Accepting up front is harmless and makes the search page
     * render its results, so the scrape below has something to read.
     */
    private static void seedSearchConsent(String target) {
        if (target == null || !target.contains("google.") || !target.contains("/search")) {
            return;
        }
        try {
            CookieManager.getInstance().setCookie("https://www.google.com",
                "CONSENT=YES+; Domain=.google.com; Path=/");
        } catch (Throwable ignored) {
            // A cookie that will not stick must not take the search with it.
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void ensureWebView() {
        if (web != null) {
            return;
        }
        web = new WebView(context);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setDatabaseEnabled(false);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);
        settings.setBlockNetworkImage(true);
        settings.setLoadsImagesAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setUserAgentString(settings.getUserAgentString() + " Luna/1.0");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
    }

    private static boolean isHeavy(String url) {
        String lower = url.toLowerCase(java.util.Locale.US);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
            || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg")
            || lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".ttf")
            || lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mp3");
    }

    private static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }
}
