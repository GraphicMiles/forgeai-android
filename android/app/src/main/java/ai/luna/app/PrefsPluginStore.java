package ai.luna.app;

import ai.luna.runtime.PluginManager;

import org.json.JSONArray;

/**
 * Installed plugins, kept in the app's own preferences.
 *
 * <p>A manifest is a small document and there are never many of them, so this
 * is the whole storage layer. When {@code Prefs} is eventually split into
 * per-domain stores this becomes the plugin store and nothing else changes.
 */
final class PrefsPluginStore implements PluginManager.Store {

    private final Prefs prefs;

    PrefsPluginStore(Prefs prefs) {
        this.prefs = prefs;
    }

    @Override
    public JSONArray load() {
        return prefs.installedPlugins();
    }

    @Override
    public void save(JSONArray manifests) {
        prefs.setInstalledPlugins(manifests);
    }
}
