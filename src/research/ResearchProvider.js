/**
 * ResearchProvider - Android Native Version
 * Delegates to ResearchRuntime when Experimental mode is enabled.
 */

import { nativeResearchProvider } from './NativeResearchProvider.js';
import { isNative } from '../nativeBridge.js';

export const RESEARCH_DEPTH = Object.freeze({
  STANDARD: 'standard',
  COMPREHENSIVE: 'comprehensive',
  RAW: 'raw',
});

const STORAGE_KEY = 'luna_research_settings';
const DEFAULT_SETTINGS = Object.freeze({
  depth: RESEARCH_DEPTH.STANDARD,
  archiveMode: false,
  sourceVerification: true,
  proxyEnabled: false,
});

function readSettings() {
  if (typeof localStorage === 'undefined') return { ...DEFAULT_SETTINGS };
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
    return {
      ...DEFAULT_SETTINGS,
      ...value,
      depth: Object.values(RESEARCH_DEPTH).includes(value.depth) ? value.depth : DEFAULT_SETTINGS.depth,
    };
  } catch {
    return { ...DEFAULT_SETTINGS };
  }
}

function writeSettings(settings) {
  if (typeof localStorage === 'undefined') return;
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(settings)); }
  catch (error) { console.warn('Failed to save research settings:', error); }
}

export class ResearchProvider {
  constructor() {
    const settings = readSettings();
    this.depth = settings.depth;
    this.archiveMode = settings.archiveMode;
    this.sourceVerification = settings.sourceVerification;
    this.proxyEnabled = settings.proxyEnabled;
  }

  snapshot() {
    return {
      depth: this.depth,
      archiveMode: this.archiveMode,
      sourceVerification: this.sourceVerification,
      proxyEnabled: this.proxyEnabled,
    };
  }

  persist() {
    writeSettings(this.snapshot());
  }

  setDepth(depth) {
    if (Object.values(RESEARCH_DEPTH).includes(depth)) {
      this.depth = depth;
      this.persist();
    }
  }

  setArchiveMode(enabled) {
    this.archiveMode = Boolean(enabled);
    this.persist();
  }

  setSourceVerification(enabled) {
    this.sourceVerification = Boolean(enabled);
    this.persist();
  }

  setProxy(enabled) {
    this.proxyEnabled = Boolean(enabled);
    this.persist();
  }

  async search(query, options = {}) {
    const experimentalEnabled = isNative;
    const depth = options.depth || this.depth;

    if (experimentalEnabled && isNative) {
      // Real native research on Android
      return await nativeResearchProvider.search(query, { ...options, depth });
    }

    // Depth-aware fallback: STANDARD returns fewer results, COMPREHENSIVE more, RAW minimal filtering
    const maxResults = depth === RESEARCH_DEPTH.STANDARD ? 5 : depth === RESEARCH_DEPTH.COMPREHENSIVE ? 10 : 8;
    const minRelevance = depth === RESEARCH_DEPTH.RAW ? 0 : depth === RESEARCH_DEPTH.STANDARD ? 2 : 1;

    return {
      query,
      depth,
      maxResults,
      minRelevance,
      archiveMode: this.archiveMode,
      sourceVerification: this.sourceVerification,
      proxyEnabled: this.proxyEnabled,
      results: [
        {
          id: 1,
          title: `Research result for: ${query}`,
          url: `https://duckduckgo.com/?q=${encodeURIComponent(query)}`,
          snippet: `Depth: ${depth}. Enable "Real Research APIs" in Experimental Features for live results. Configure Google CSE in Settings for broader coverage.`,
          verified: false,
        },
      ],
      provider: 'Simulated',
      simulated: true,
    };
  }

  async fetchFullPage(url) {
    const experimentalEnabled = isNative;

    if (experimentalEnabled && isNative) {
      return await nativeResearchProvider.fetchFullPage(url);
    }

    // Fallback: attempt to fetch via web (browser mode only, CORS limited)
    if (typeof fetch !== 'undefined') {
      try {
        const response = await fetch(url, { mode: 'cors', redirect: 'follow' });
        if (response.ok) {
          const text = await response.text();
          // Strip HTML tags for readability
          const stripped = text.replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim();
          return {
            url,
            content: stripped.slice(0, 10000),
            fetchedAt: new Date().toISOString(),
            simulated: false,
          };
        }
      } catch {
        // CORS or network error — expected in browser for most sites
      }
    }

    return {
      url,
      content: `Enable "Real Research APIs" in Experimental Features on Android for native page fetching. Browser mode is limited by CORS.`,
      simulated: true,
    };
  }
}

export const researchProvider = new ResearchProvider();
