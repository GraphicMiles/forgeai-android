package ai.luna.app;

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
public final class HeadlessBrowser {

    private static final long DEFAULT_TIMEOUT_MS = 20000L;
    private static final int MAX_TEXT = 40000;

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
        if (web == null) {
            return "";
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<String> holder = new AtomicReference<>("");
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    web.evaluateJavascript(
                        "(function(){var c=document.querySelector('article')||document.body;"
                            + "if(!c)return '';var clone=c.cloneNode(true);"
                            + "var junk=clone.querySelectorAll('script,style,noscript,nav,footer,header,aside');"
                            + "for(var i=0;i<junk.length;i++){junk[i].remove();}"
                            + "return clone.innerText;})();",
                        value -> {
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
        return ReadableText.clean(holder.get(), MAX_TEXT);
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
