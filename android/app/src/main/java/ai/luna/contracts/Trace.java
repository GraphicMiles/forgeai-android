package ai.luna.contracts;

/**
 * Somewhere to say what happened.
 *
 * <p>The runtime writes lines; who stores them is not the runtime's business.
 * Today that is Luna's ErrorLog, feeding the debug panel. Tomorrow it could be
 * a file on a server, and nothing above this interface changes.
 */
public interface Trace {

    void note(String where, String what);

    void warn(String where, String what);

    void fail(String where, String what);

    /** Writes nowhere. Handy in tests and for a runtime with no panel. */
    Trace SILENT = new Trace() {
        @Override
        public void note(String where, String what) {
        }

        @Override
        public void warn(String where, String what) {
        }

        @Override
        public void fail(String where, String what) {
        }
    };
}
