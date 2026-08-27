import { searchOnline, fetchPublicUrl, isNative } from '../nativeBridge.js';

export function isOnlineResearchRequest(message = '') {
  const text = String(message).toLowerCase();
  // Original keyword-based triggers
  if (/\b(search online|research|latest|current|today|news|who won|score|result|how old|age of|github repo|pull request|workflow run|fact.?check)\b/i.test(text)) return true;
  // Time-sensitive query patterns (schedules, dates, seasons, current events)
  if (/\b(when is|when does|when will|when was)\b/i.test(text)) return true;
  if (/\b(2024|2025|2026|this year|last year|this season|last season|next season)\b/i.test(text)) return true;
  if (/\b(start|start(s|ed|ing)|begin|release|launch|premiere)\b.*\b(date|when|time)\b/i.test(text)) return true;
  if (/\b(still|currently|now)\b.*\b(play|work|live|coach|manage|at|for|with)\b/i.test(text)) return true;
  if (/\b(which club|which team|which company)\b.*\b(currently|now|play|work)\b/i.test(text)) return true;
  // World Cup, Olympics, major events with year
  if (/\b(world cup|olympics|super bowl|champions league|premier league|nba|nfl|mlb)\b.*\b(2024|2025|2026|next|last|this)\b/i.test(text)) return true;
  // "Best/top/most" queries — these are inherently time-sensitive
  if (/\b(best|top|most popular|most used|leading|number one|#1|greatest)\b.*\b(ai|model|language|framework|library|tool|platform|company|app|game|movie|song|player|team)\b/i.test(text)) return true;
  if (/\b(who is|what is)\b.*\b(best|top|most|leading|greatest)\b/i.test(text)) return true;
  // Biographical / definitional lookups: "who is <Name>", "tell me about <Name>",
  // "who was Ada Lovelace". These need live sources for anyone the model may not
  // know or whose situation changes over time. Excludes code-context words
  // ("this", "that", "the function/file/code/error") so "what is this function?"
  // still routes to normal chat instead of web search.
  if (isBiographicalLookup(text)) return true;
  return false;
}

// A person/entity lookup like "who is lamine yamal" or "tell me about SpaceX".
// People rarely capitalise names on mobile, so this does NOT rely on casing.
// Instead it fires on a biographical trigger with a real subject, and bows out
// of self-referential code questions (which are the common false positive).
function isBiographicalLookup(rawMessage = '') {
  const text = String(rawMessage).trim().toLowerCase();
  // Words that mean "the thing we're already looking at" — a strong signal the
  // question is about code/context, not an external person or entity.
  const CODE_CONTEXT = /\b(this|that|these|those|it|above|below|following|function|method|class|component|variable|file|code|error|bug|line|snippet|repo|repository|project|output|result|he|she|they|him|her|them)\b/;
  const bioTrigger = /\b(who\s+(?:is|are|was|were)|tell me about|what do you know about|info(?:rmation)? (?:about|on)|biography of|how old is)\b/i;
  if (!bioTrigger.test(text)) return false;
  if (CODE_CONTEXT.test(text)) return false;
  // Require a real subject after the trigger (at least one 3+ char word), so
  // bare "who is" / "tell me about" without a target falls through to chat.
  const afterTrigger = text.replace(/^.*?\b(?:who\s+(?:is|are|was|were)|tell me about|what do you know about|about|on|of)\b/i, '').trim();
  const subjectWords = afterTrigger.split(/\s+/).filter(word => word.replace(/[^a-z0-9]/g, '').length >= 3);
  return subjectWords.length >= 1;
}

function stripMarkup(value) {
  return String(value || '').replace(/<[^>]+>/g, '').replace(/\s+/g, ' ').trim().slice(0, 1000);
}

// Google News RSS titles arrive as "Headline - Publisher". Split the publisher off
// so cards can show a clean title and a source name instead of a raw URL.
const TITLE_PUBLISHER_SEPARATOR = /\s[-–—]\s([^-–—]{2,60})$/;

function splitPublisher(rawTitle) {
  const match = rawTitle.match(TITLE_PUBLISHER_SEPARATOR);
  if (!match) return { title: rawTitle, publisher: '' };
  const title = rawTitle.slice(0, match.index).trim();
  if (!title) return { title: rawTitle, publisher: '' };
  return { title, publisher: match[1].trim() };
}

// Deterministic relevance rank: count distinct query terms (4+ chars) present in the
// title/snippet. Keeps the most useful evidence first so the small on-device model
// anchors on the right sources instead of the first RSS entries.
function relevanceScore(query, item) {
  const terms = new Set(
    String(query).toLowerCase().replace(/[^a-z0-9\s]/g, ' ').split(/\s+/).filter(word => word.length >= 4),
  );
  if (!terms.size) return 0;
  const haystack = `${item.title} ${item.snippet}`.toLowerCase();
  let score = 0;
  for (const term of terms) {
    if (haystack.includes(term)) score += 1;
  }
  return score;
}

export async function performOnlineResearch(query, { maxRetries = 2, backoffMs = 1000 } = {}) {
  const googleApiKey = localStorage.getItem('luna_google_api_key') || '';
  const googleCx = localStorage.getItem('luna_google_cx') || '';
  
  let lastError;
  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      const result = await searchOnline({ query, googleApiKey, googleCx });
      const parsed = Array.from(result.items || []).map((item) => {
        const { title, publisher } = splitPublisher(stripMarkup(item.title));
        return {
          title,
          publisher,
          url: String(item.url || ''),
          snippet: stripMarkup(item.snippet),
          source: String(item.source || result.provider || 'web'),
        };
      }).filter(item => item.url.startsWith('https://') && item.title);
      if (!parsed.length) throw new Error('No public research sources were returned. Configure Google Programmable Search for broader results.');
      const items = parsed
        .map((item, index) => ({ item, index, score: relevanceScore(query, item) }))
        .sort((a, b) => (b.score - a.score) || (a.index - b.index))
        .slice(0, 8)
        .map(({ item }, index) => ({ ...item, id: index + 1 }));
      const evidence = items.map(item => `[${item.id}] ${item.title}\nSource: ${item.publisher || item.source}\nURL: ${item.url}\nEvidence: ${item.snippet}`).join('\n\n');
      return { query, provider: result.provider, searchedAt: result.searchedAt, items, evidence };
    } catch (error) {
      lastError = error;
      // Retry on rate limit (429) or server errors (5xx)
      const isRetryable = /429|5\d{2}|rate.?limit|too many|temporarily unavailable/i.test(error.message || '');
      if (isRetryable && attempt < maxRetries) {
        await new Promise(resolve => setTimeout(resolve, backoffMs * Math.pow(2, attempt)));
        continue;
      }
      throw error;
    }
  }
  throw lastError;
}

// Best-effort og:image / twitter:image extraction for source preview cards.
// Native-only: the browser cannot fetch arbitrary cross-origin pages. Any failure
// simply leaves that source without a thumbnail.
const OG_IMAGE_PATTERNS = [
  /<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']/i,
  /<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:image["']/i,
  /<meta[^>]+name=["']twitter:image(?::src)?["'][^>]+content=["']([^"']+)["']/i,
  /<meta[^>]+content=["']([^"']+)["'][^>]+name=["']twitter:image(?::src)?["']/i,
];

export async function fetchSourcePreviews(items, { limit = 4 } = {}) {
  const previews = new Map();
  if (!isNative || !Array.isArray(items)) return previews;
  await Promise.all(items.slice(0, limit).map(async (item) => {
    if (!item?.url) return;
    try {
      const page = await fetchPublicUrl(item.url);
      const html = String(page?.content || '').slice(0, 250000);
      for (const pattern of OG_IMAGE_PATTERNS) {
        const candidate = html.match(pattern)?.[1]?.replace(/&amp;/g, '&').trim();
        if (candidate && /^https:\/\//i.test(candidate)) {
          previews.set(item.url, candidate);
          break;
        }
      }
    } catch {
      // No preview for this source — the card falls back to the globe icon.
    }
  }));
  return previews;
}
