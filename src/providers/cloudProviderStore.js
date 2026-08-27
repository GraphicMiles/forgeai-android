const STORAGE_KEY = 'luna_cloud_providers_v1';
const FAILOVER_KEY = 'luna_provider_failover_v1';

/**
 * Cloud provider catalog.
 *
 * Every entry is OpenAI-compatible (works with OpenAICompatibleProvider) unless
 * noted. Rich metadata powers the UI: how to get a key, docs link, expected key
 * prefix (for the mismatch hint), whether a card is needed, and a free-tier
 * summary so users know what they're getting.
 */
export const CLOUD_PROVIDER_PRESETS = Object.freeze([
  Object.freeze({
    id: 'groq', label: 'Groq', baseUrl: 'https://api.groq.com/openai/v1',
    defaultModel: 'llama-3.3-70b-versatile', keyPrefix: 'gsk_', card: false,
    models: ['llama-3.3-70b-versatile', 'llama-3.1-8b-instant', 'openai/gpt-oss-120b', 'openai/gpt-oss-20b', 'qwen/qwen3-32b', 'moonshotai/kimi-k2-instruct', 'deepseek-r1-distill-llama-70b'],
    freeTier: '30 RPM · ~14,400 req/day · very fast (LPU)',
    keyUrl: 'https://console.groq.com/keys', docs: 'https://console.groq.com/docs',
    howTo: 'Sign up (no card) → API Keys → Create API Key. Paste the gsk_ key.',
  }),
  Object.freeze({
    id: 'cerebras', label: 'Cerebras', baseUrl: 'https://api.cerebras.ai/v1',
    defaultModel: 'qwen-3-235b-a22b-instruct-2507', keyPrefix: 'csk-', card: false,
    models: ['qwen-3-235b-a22b-instruct-2507', 'gpt-oss-120b', 'llama-3.3-70b', 'qwen-3-32b', 'llama3.1-8b'],
    freeTier: '~30 RPM · 1,000,000 tokens/day (highest free quota)',
    keyUrl: 'https://cloud.cerebras.ai/', docs: 'https://inference-docs.cerebras.ai/',
    howTo: 'Sign up at cloud.cerebras.ai (no card) → API Keys → generate a key. Bigger models (Qwen-3 235B) are far stronger than 70B — prefer them.',
  }),
  Object.freeze({
    id: 'google', label: 'Google Gemini', baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai',
    defaultModel: 'gemini-2.5-flash', keyPrefix: 'AIza', card: false,
    models: ['gemini-2.5-pro', 'gemini-2.5-flash', 'gemini-2.5-flash-lite', 'gemini-2.0-flash'],
    freeTier: '~1,500 req/day · up to 1M-token context',
    keyUrl: 'https://aistudio.google.com/apikey', docs: 'https://ai.google.dev/gemini-api/docs/openai',
    howTo: 'Open Google AI Studio → Get API key → Create API key. Uses the OpenAI-compatible endpoint.',
  }),
  Object.freeze({
    id: 'openrouter', label: 'OpenRouter', baseUrl: 'https://openrouter.ai/api/v1',
    defaultModel: 'deepseek/deepseek-chat-v3.1:free', keyPrefix: 'sk-or-', card: false,
    models: ['deepseek/deepseek-chat-v3.1:free', 'deepseek/deepseek-r1:free', 'qwen/qwen3-coder:free', 'meta-llama/llama-3.3-70b-instruct:free', 'google/gemini-2.0-flash-exp:free', 'anthropic/claude-3.5-sonnet', 'openai/gpt-4o'],
    freeTier: '~50 req/day free (1K/day after $10) · one key → ~28 free models',
    keyUrl: 'https://openrouter.ai/keys', docs: 'https://openrouter.ai/docs',
    howTo: 'Sign up → Keys → Create Key. ":free" models cost nothing; paid models (claude/gpt) need credits.',
  }),
  Object.freeze({
    id: 'mistral', label: 'Mistral AI', baseUrl: 'https://api.mistral.ai/v1',
    defaultModel: 'mistral-large-latest', keyPrefix: '', card: false,
    models: ['mistral-large-latest', 'mistral-medium-latest', 'codestral-latest', 'devstral-medium-latest', 'mistral-small-latest'],
    freeTier: 'Free experiment tier · ~1B tokens/month · Codestral for code',
    keyUrl: 'https://console.mistral.ai/api-keys', docs: 'https://docs.mistral.ai/',
    howTo: 'Sign up at console.mistral.ai → API Keys → Create new key.',
  }),
  Object.freeze({
    id: 'github', label: 'GitHub Models', baseUrl: 'https://models.github.ai/inference',
    defaultModel: 'openai/gpt-4o', keyPrefix: 'ghp_', card: false,
    models: ['openai/gpt-4o', 'openai/gpt-4.1', 'openai/o4-mini', 'meta/Llama-3.3-70B-Instruct', 'mistral-ai/Mistral-Large-2411', 'deepseek/DeepSeek-V3-0324'],
    freeTier: 'Free with GitHub account · 100+ models · ~50–150 req/day',
    keyUrl: 'https://github.com/settings/tokens', docs: 'https://docs.github.com/github-models',
    howTo: 'Create a GitHub fine-grained/classic token (Settings → Developer settings → Tokens). Use it as the API key.',
  }),
  Object.freeze({
    id: 'cloudflare', label: 'Cloudflare Workers AI', baseUrl: 'https://api.cloudflare.com/client/v4/accounts/ACCOUNT_ID/ai/v1',
    defaultModel: '@cf/meta/llama-3.3-70b-instruct-fp8-fast', keyPrefix: '', card: false,
    models: ['@cf/meta/llama-3.3-70b-instruct-fp8-fast', '@cf/openai/gpt-oss-120b', '@cf/qwen/qwen2.5-coder-32b-instruct', '@cf/meta/llama-4-scout-17b-16e-instruct'],
    freeTier: '10,000 neurons/day · ~35 models',
    keyUrl: 'https://dash.cloudflare.com/profile/api-tokens', docs: 'https://developers.cloudflare.com/workers-ai/',
    howTo: 'Create an API token with Workers AI permission, then replace ACCOUNT_ID in the base URL with your Cloudflare account ID.',
  }),
  Object.freeze({
    id: 'deepseek', label: 'DeepSeek', baseUrl: 'https://api.deepseek.com/v1',
    defaultModel: 'deepseek-chat', keyPrefix: 'sk-', card: false,
    models: ['deepseek-chat', 'deepseek-reasoner'],
    freeTier: '~5M free tokens on signup · very cheap after',
    keyUrl: 'https://platform.deepseek.com/api_keys', docs: 'https://api-docs.deepseek.com/',
    howTo: 'Sign up at platform.deepseek.com → API keys → Create. deepseek-reasoner = R1 reasoning model.',
  }),
  Object.freeze({
    id: 'nvidia', label: 'NVIDIA NIM', baseUrl: 'https://integrate.api.nvidia.com/v1',
    defaultModel: 'qwen/qwen3-235b-a22b', keyPrefix: 'nvapi-', card: false,
    models: ['qwen/qwen3-235b-a22b', 'deepseek-ai/deepseek-r1', 'meta/llama-3.3-70b-instruct', 'meta/llama-4-maverick-17b-128e-instruct', 'nvidia/llama-3.3-nemotron-super-49b-v1'],
    freeTier: '~40 RPM · 100+ models (phone verification)',
    keyUrl: 'https://build.nvidia.com/', docs: 'https://docs.nvidia.com/nim/',
    howTo: 'Sign up at build.nvidia.com → pick a model → "Get API Key" (nvapi- key).',
  }),
  Object.freeze({
    id: 'sambanova', label: 'SambaNova', baseUrl: 'https://api.sambanova.ai/v1',
    defaultModel: 'Meta-Llama-3.3-70B-Instruct', keyPrefix: '', card: false,
    models: ['Llama-4-Maverick-17B-128E-Instruct', 'Meta-Llama-3.3-70B-Instruct', 'DeepSeek-R1', 'Qwen3-32B', 'Meta-Llama-3.1-405B-Instruct'],
    freeTier: 'Free developer tier · Llama up to 405B · 10–30 RPM',
    keyUrl: 'https://cloud.sambanova.ai/apis', docs: 'https://docs.sambanova.ai/',
    howTo: 'Sign up at cloud.sambanova.ai → APIs → generate a key.',
  }),
  Object.freeze({
    id: 'cohere', label: 'Cohere', baseUrl: 'https://api.cohere.ai/compatibility/v1',
    defaultModel: 'command-a-03-2025', keyPrefix: '', card: false,
    models: ['command-a-03-2025', 'command-r-plus-08-2024', 'command-r-08-2024'],
    freeTier: '~1,000 calls/month (trial keys; terms restrict personal use)',
    keyUrl: 'https://dashboard.cohere.com/api-keys', docs: 'https://docs.cohere.com/docs/compatibility-api',
    howTo: 'Sign up at dashboard.cohere.com → API Keys → use a Trial key.',
  }),
  Object.freeze({
    id: 'together', label: 'Together AI', baseUrl: 'https://api.together.xyz/v1',
    defaultModel: 'meta-llama/Llama-3.3-70B-Instruct-Turbo', keyPrefix: '', card: true,
    models: ['deepseek-ai/DeepSeek-R1', 'Qwen/Qwen2.5-Coder-32B-Instruct', 'meta-llama/Llama-3.3-70B-Instruct-Turbo', 'meta-llama/Meta-Llama-3.1-405B-Instruct-Turbo'],
    freeTier: '$1 free credits · 200+ open models',
    keyUrl: 'https://api.together.ai/settings/api-keys', docs: 'https://docs.together.ai/',
    howTo: 'Sign up at together.ai → Settings → API Keys.',
  }),
  Object.freeze({
    id: 'fireworks', label: 'Fireworks AI', baseUrl: 'https://api.fireworks.ai/inference/v1',
    defaultModel: 'accounts/fireworks/models/llama-v3p3-70b-instruct', keyPrefix: 'fw_', card: true,
    models: ['accounts/fireworks/models/deepseek-r1', 'accounts/fireworks/models/qwen2p5-coder-32b-instruct', 'accounts/fireworks/models/llama-v3p3-70b-instruct', 'accounts/fireworks/models/llama4-maverick-instruct-basic'],
    freeTier: '$1 free credits · top open models',
    keyUrl: 'https://fireworks.ai/account/api-keys', docs: 'https://docs.fireworks.ai/',
    howTo: 'Sign up at fireworks.ai → Account → API Keys.',
  }),
  Object.freeze({
    id: 'openai', label: 'OpenAI', baseUrl: 'https://api.openai.com/v1',
    defaultModel: 'gpt-4.1-mini', keyPrefix: 'sk-', card: true,
    models: ['gpt-4.1', 'gpt-4o', 'gpt-4.1-mini', 'o4-mini', 'gpt-4o-mini'],
    freeTier: 'No free tier (pay-as-you-go); industry standard',
    keyUrl: 'https://platform.openai.com/api-keys', docs: 'https://platform.openai.com/docs',
    howTo: 'platform.openai.com → API keys → Create new secret key (billing required).',
  }),
  Object.freeze({
    id: 'xai', label: 'xAI / Grok', baseUrl: 'https://api.x.ai/v1',
    defaultModel: 'grok-4', keyPrefix: 'xai-', card: true,
    models: ['grok-4', 'grok-4-fast-reasoning', 'grok-3', 'grok-3-mini'],
    freeTier: 'Signup credits (varies); pay-as-you-go',
    keyUrl: 'https://console.x.ai/', docs: 'https://docs.x.ai/',
    howTo: 'console.x.ai → API Keys → Create.',
  }),
  Object.freeze({
    id: 'nebius', label: 'Nebius AI', baseUrl: 'https://api.studio.nebius.com/v1',
    defaultModel: 'deepseek-ai/DeepSeek-R1', keyPrefix: '', card: false,
    models: ['deepseek-ai/DeepSeek-R1', 'Qwen/Qwen3-235B-A22B', 'meta-llama/Llama-3.3-70B-Instruct', 'Qwen/Qwen2.5-Coder-32B-Instruct'],
    freeTier: 'Free trial credits · open models',
    keyUrl: 'https://studio.nebius.com/', docs: 'https://docs.nebius.com/studio/inference',
    howTo: 'Sign up at studio.nebius.com → API keys.',
  }),
  Object.freeze({
    id: 'ollama', label: 'Ollama Cloud', baseUrl: 'https://ollama.com/v1',
    defaultModel: 'gpt-oss:120b', keyPrefix: '', card: false,
    models: ['gpt-oss:120b', 'qwen3-coder:480b', 'deepseek-v3.1:671b', 'kimi-k2:1t'],
    freeTier: 'Free cloud tier (session/weekly caps) · open models',
    keyUrl: 'https://ollama.com/settings/keys', docs: 'https://docs.ollama.com/',
    howTo: 'Sign up at ollama.com → Settings → Keys.',
  }),
  Object.freeze({
    id: 'custom', label: 'Custom OpenAI-compatible', baseUrl: '', defaultModel: '', keyPrefix: '', card: false,
    models: [],
    freeTier: 'Any OpenAI-compatible endpoint',
    keyUrl: '', docs: '',
    howTo: 'Enter the base URL (must end in /v1 or similar), a model id, and your API key.',
  }),
]);

export function getCloudProviderPreset(provider) {
  return CLOUD_PROVIDER_PRESETS.find(item => item.id === provider) || CLOUD_PROVIDER_PRESETS.at(-1);
}

// Known API key prefixes → provider, for a soft mismatch hint in the UI.
export const KNOWN_KEY_PREFIXES = Object.freeze(
  CLOUD_PROVIDER_PRESETS
    .filter(p => p.keyPrefix)
    .map(p => ({ prefix: p.keyPrefix, provider: p.id, label: p.label })),
);

function readAll() {
  if (typeof localStorage === 'undefined') return [];
  try {
    const value = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]');
    return Array.isArray(value) ? value.filter(item => item && typeof item.id === 'string') : [];
  } catch {
    return [];
  }
}

function writeAll(value) {
  if (typeof localStorage === 'undefined') return;
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(value)); }
  catch (error) { console.warn('Failed to save cloud provider settings:', error); }
}

export function listCloudProviders() {
  return readAll();
}

export function getCloudProvider(id) {
  return readAll().find(item => item.id === id) || null;
}

export function saveCloudProvider(input = {}) {
  const preset = getCloudProviderPreset(input.provider || 'custom');
  const provider = input.provider || preset.id;
  const baseUrl = String(input.baseUrl || preset.baseUrl || '').trim().replace(/\/$/, '');
  const modelId = String(input.modelId || input.defaultModel || preset.defaultModel || '').trim();
  const apiKey = String(input.apiKey || '').trim();
  const label = String(input.label || preset.label || provider).trim();

  if (!provider) throw new Error('Cloud provider is required.');
  if (!label) throw new Error('Cloud provider label is required.');
  if (!baseUrl) throw new Error('Cloud provider base URL is required.');
  if (!/^https?:\/\//i.test(baseUrl)) throw new Error('Cloud provider base URL must start with http:// or https://.');
  if (/ACCOUNT_ID/.test(baseUrl)) throw new Error('Replace ACCOUNT_ID in the base URL with your Cloudflare account ID first.');
  if (!modelId) throw new Error('Cloud model id is required.');
  if (!apiKey) throw new Error('Cloud API key is required.');

  const now = Date.now();
  const id = input.id || `cloud-${provider}-${now}-${Math.random().toString(36).slice(2, 8)}`;
  const entry = {
    id,
    provider,
    label,
    baseUrl,
    modelId,
    apiKey,
    // Lower number = tried earlier during failover. Defaults to append order.
    priority: Number.isFinite(input.priority) ? input.priority : (readAll().length + 1),
    createdAt: input.createdAt || now,
    updatedAt: now,
  };
  const next = [...readAll().filter(item => item.id !== id), entry];
  writeAll(next);
  return entry;
}

export function removeCloudProvider(id) {
  const next = readAll().filter(item => item.id !== id);
  writeAll(next);
  return next;
}

// --- Failover preference (on/off) -----------------------------------------
export function isFailoverEnabled() {
  if (typeof localStorage === 'undefined') return true;
  const v = localStorage.getItem(FAILOVER_KEY);
  return v === null ? true : v === 'true';
}
export function setFailoverEnabled(enabled) {
  if (typeof localStorage !== 'undefined') {
    try { localStorage.setItem(FAILOVER_KEY, String(!!enabled)); } catch { /* ignore */ }
  }
  return !!enabled;
}

/**
 * Fetch the LIVE model list from a provider's /models endpoint using the entered
 * key. Model ids drift between providers/accounts, so the curated presets are
 * only a starting point — this returns exactly what the account can use.
 *
 * @param {object} conn { baseUrl, apiKey }
 * @returns {Promise<string[]>} sorted model ids (throws with a friendly message on failure)
 */
export async function fetchProviderModels({ baseUrl, apiKey } = {}) {
  const base = String(baseUrl || '').trim().replace(/\/$/, '');
  const key = String(apiKey || '').trim();
  if (!base) throw new Error('Enter the base URL first.');
  if (/ACCOUNT_ID/.test(base)) throw new Error('Replace ACCOUNT_ID in the base URL first.');
  if (!key) throw new Error('Enter the API key first.');

  let res;
  try {
    res = await fetch(`${base}/models`, { headers: { Authorization: `Bearer ${key}` } });
  } catch {
    throw new Error('Could not reach the provider. Check the base URL and your connection.');
  }
  if (res.status === 401 || res.status === 403) throw new Error('The API key was rejected. Check the key for this provider.');
  if (!res.ok) throw new Error(`Provider returned HTTP ${res.status} for /models. It may not support listing models — enter the id manually.`);

  let payload = null;
  try { payload = await res.json(); } catch { throw new Error('Provider did not return a model list. Enter the model id manually.'); }
  // OpenAI shape: { data: [{ id }] }. Some return { models: [...] } or a bare array.
  const raw = Array.isArray(payload?.data) ? payload.data
    : Array.isArray(payload?.models) ? payload.models
    : Array.isArray(payload) ? payload : [];
  const ids = raw
    .map(m => (typeof m === 'string' ? m : m?.id || m?.name || m?.model))
    .filter(id => typeof id === 'string' && id.trim())
    .map(id => id.trim());
  if (!ids.length) throw new Error('The provider returned no models for this key. Enter the model id manually.');
  return [...new Set(ids)].sort((a, b) => a.localeCompare(b));
}

export function cloudProviderToModel(provider) {
  const preset = getCloudProviderPreset(provider.provider);
  return {
    id: `cloud-model-${provider.id}`,
    name: provider.label || preset.label,
    source: 'cloud',
    provider: provider.provider,
    providerLabel: preset.label,
    connectionId: provider.id,
    modelId: provider.modelId,
    params: 'Cloud',
    file: provider.modelId,
    size: 0,
    sizeUnit: 'API',
    sizeBytes: 0,
    minRam: 0,
    task: /coder|code/i.test(provider.modelId) ? 'coding' : 'chat',
    capabilities: ['chat', 'code', 'reasoning'],
    contextTokens: 8192,
    quotaType: 'provider-api',
    privacy: 'cloud',
    cloud: true,
    status: 'ready',
  };
}

export function cloudProvidersToModels(providers = readAll()) {
  return providers.map(cloudProviderToModel);
}
