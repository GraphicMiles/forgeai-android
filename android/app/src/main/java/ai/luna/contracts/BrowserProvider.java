package ai.luna.contracts;

/**
 * Something that can open a page and hand back its text.
 *
 * <p>The Android implementation is the system WebView with no window. A desktop
 * or server runtime can put Playwright or a remote browser behind the same four
 * methods, and the tool that uses it does not change.
 */
public interface BrowserProvider {

    String id();

    /** Empty when the page loaded, otherwise the reason it did not. */
    String open(String url, long timeoutMs);

    /** The page as readable text, or empty when nothing is open. */
    String text();

    /**
     * Structured search results from the open page: one result per line as
     * {@code title || address || snippet}, or empty when the page has none.
     * Kept on the contract so a desktop or server browser can supply its own
     * extractor; the default is honest about not knowing the page's shape.
     */
    default String searchResults() {
        return "";
    }

    String currentUrl();

    String currentTitle();

    /** End the session and forget its cookies. */
    void close();
}
