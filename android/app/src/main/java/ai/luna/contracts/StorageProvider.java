package ai.luna.contracts;

import org.json.JSONArray;

import java.io.IOException;

/**
 * Where files live, whichever "where" that is.
 *
 * <p>Luna's WorkspaceStore — one Android folder granted through SAF — is the
 * first implementation and remains the default. The contract exists so that a
 * git checkout, a Docker volume, a laptop directory or a remote box can be the
 * same thing to a tool. Paths are always relative to the provider's own root;
 * there is no path that escapes it, because there is nothing above it to name.
 */
public interface StorageProvider {

    /** Stable id: {@code android.saf}, {@code local.fs}, {@code git.repo}, … */
    String id();

    /** Is there a root to work in at all? */
    boolean hasRoot();

    /** {@code none}, {@code ok} or {@code revoked}. */
    String rootState();

    /** What to call the root on screen. */
    String rootName();

    /** Entries directly under {@code path}: name, type, size. */
    JSONArray list(String path) throws Exception;

    String readText(String path) throws IOException;

    /**
     * Writes the file and returns the path it actually landed on, relative to
     * the root. A provider may not be able to honour the name it was given —
     * Android's document providers rewrite extensions — so the caller is told
     * what happened rather than what was asked for.
     */
    String writeText(String path, String content) throws IOException;

    /** Creates the file and returns the path it actually landed on. */
    String createFile(String path) throws IOException;

    void createFolder(String path) throws IOException;

    void rename(String path, String newName) throws IOException;

    void delete(String path) throws IOException;

    /** Matches for {@code needle}, capped at {@code limit}, with line numbers. */
    JSONArray search(String needle, int limit) throws Exception;
}
