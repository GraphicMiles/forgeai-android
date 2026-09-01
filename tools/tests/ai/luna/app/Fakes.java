package ai.luna.app;

import org.json.JSONArray;

/**
 * Stand-ins for the device, so the runtime can be tested on a plain JVM.
 *
 * <p>They do nothing on purpose: these tests are about what the runtime decides
 * to offer and to run, never about what a file or a page contains.
 */
final class Fakes {

    private Fakes() {
    }

    /** Storage that exists but is never actually read in these tests. */
    static final class FakeStorage implements ai.luna.contracts.StorageProvider {
        @Override
        public String id() {
            return "test.storage";
        }

        @Override
        public boolean hasRoot() {
            return true;
        }

        @Override
        public String rootState() {
            return "ok";
        }

        @Override
        public String rootName() {
            return "Test";
        }

        @Override
        public JSONArray list(String path) {
            return new JSONArray();
        }

        @Override
        public String readText(String path) {
            return "";
        }

        @Override
        public void writeText(String path, String content) {
        }

        @Override
        public void createFile(String path) {
        }

        @Override
        public void createFolder(String path) {
        }

        @Override
        public void rename(String path, String newName) {
        }

        @Override
        public void delete(String path) {
        }

        @Override
        public JSONArray search(String needle, int limit) {
            return new JSONArray();
        }
    }

    static final class FakeBrowser implements ai.luna.contracts.BrowserProvider {
        @Override
        public String id() {
            return "test.browser";
        }

        @Override
        public String open(String url, long timeoutMs) {
            return "";
        }

        @Override
        public String text() {
            return "";
        }

        @Override
        public String currentUrl() {
            return "";
        }

        @Override
        public String currentTitle() {
            return "";
        }

        @Override
        public void close() {
        }
    }

    /** Git that exists but does nothing; availability is what these tests check. */
    static final class FakeGit implements ai.luna.contracts.GitProvider {
        @Override
        public String id() {
            return "test.git";
        }

        @Override
        public String clone(String url, String name, String token) {
            return "";
        }

        @Override
        public String pull(String path, String token) {
            return "";
        }

        @Override
        public String push(String path, String token) {
            return "";
        }

        @Override
        public String status(String path) {
            return "";
        }

        @Override
        public String commit(String path, String message) {
            return "";
        }

        @Override
        public String log(String path, int limit) {
            return "";
        }

        @Override
        public String diff(String path) {
            return "";
        }
    }
}
