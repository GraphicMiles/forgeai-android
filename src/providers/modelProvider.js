import {
  checkOllamaConnection,
  getOnDeviceRuntimeInfo,
  loadOnDeviceModel,
  runOnDeviceChat,
  streamOllamaChat,
  unloadOnDeviceModel,
} from '../nativeBridge.js';
import { getCloudProvider, getCloudProviderPreset } from './cloudProviderStore.js';
import { parseLlamaFunctionSyntax } from '../agent/toolSchemas.js';

export class OllamaProvider {
  constructor(url = 'http://localhost:11434') { this.url = url; this.kind = 'ollama'; this.supportsToolUse = true; }
  async getStatus() { const result = await checkOllamaConnection(this.url); return { ...result, kind: this.kind }; }
  async loadModel() { return { loaded: true, reused: true, loadMs: 0 }; }
  async stream({ model, messages, signal, onToken }) {
    const modelName = typeof model === 'string' ? model : model?.ollamaName || model?.id;
    if (!modelName) throw new Error('An Ollama model name is required.');
    return streamOllamaChat({ url: this.url, model: modelName, messages, signal, onToken });
  }
  async stop() { return { stopped: true }; }
  async unloadModel() { return { unloaded: true }; }
}

export class OnDeviceProvider {
  // Sub-1B GGUF models are unreliable at multi-step tool-call JSON, so the
  // app keeps them on the guided keyword-gated path rather than the agentic loop.
  constructor() { this.kind = 'on-device'; this.supportsToolUse = false; }
  async getStatus() { return { ...(await getOnDeviceRuntimeInfo()), kind: this.kind }; }
  async loadModel(model) {
    if (!model?.localPath) throw new Error('Select a downloaded offline model first.');
    if (typeof model.localPath !== 'string' || !model.localPath.startsWith('/')) throw new Error(`Invalid Android runtime model path: ${model.localPath}`);
    const status = await getOnDeviceRuntimeInfo();
    if (status.loaded && status.loadedModelId === model.id && status.loadedPath === model.localPath) {
      return { loaded: true, reused: true, modelId: model.id, loadMs: status.lastLoadMs || 0 };
    }
    try { return await loadOnDeviceModel(model); }
    catch (error) {
      const wrapped = new Error(`Native model load failed: ${error.message || 'unknown error'}`);
      wrapped.code = error.code || 'MODEL_LOAD_FAILED';
      throw wrapped;
    }
  }
  async stream({ model, messages, signal, onToken }) {
    return runOnDeviceChat({ model, messages, signal, onToken });
  }
  async stop() { return { stopped: true }; }
  async unloadModel() { return unloadOnDeviceModel(); }
}

function normalizeCloudError(error, { providerConfig, model } = {}) {
  const providerName = getCloudProviderPreset(providerConfig?.provider || model?.provider || 'custom')?.label || 'Cloud provider';
  const raw = `${error?.message || error || ''}`;
  const lower = raw.toLowerCase();
  let code = error?.code || 'cloud_error';
  let message = `${providerName} request failed: ${raw || 'unknown error'}`;

  if (error?.status === 401 || error?.status === 403 || /(?:invalid|incorrect)[\s\S]{0,30}key|unauthorized|forbidden|authentication/.test(lower)) {
    code = 'invalid_api_key';
    message = `The API key for ${providerName} appears to be invalid, expired, or unauthorized. Update it in My Collection → Cloud Provider.`;
  } else if (error?.status === 402 || /insufficient_quota|quota_exceeded|billing|token balance|credit|exhausted|used up|hard_limit/.test(lower)) {
    code = 'quota_exceeded';
    message = `Your ${providerName} quota, credits, or token balance appears to be used up. Check billing/quota, switch cloud providers, or select a local GGUF model with no API token quota.`;
  } else if (error?.status === 429 || /rate.?limit|too many requests|temporarily overloaded/.test(lower)) {
    code = 'rate_limited';
    message = `${providerName} is rate limiting requests. Wait a moment, choose a different cloud model, or switch to a local GGUF model.`;
  } else if (/failed to fetch|fetch failed|network|offline|internet|timeout|timed out|econn|enotfound|dns|socket|aborted/.test(lower)) {
    code = lower.includes('aborted') ? 'aborted' : 'network_error';
    message = code === 'aborted'
      ? 'Cloud generation was cancelled.'
      : `Cloud model unavailable because the network request failed. Check internet connectivity or switch to a local GGUF model.`;
  } else if (error?.status === 404 || /model.*not.*found|not found/.test(lower)) {
    code = 'model_not_found';
    message = `${providerName} could not find model "${model?.modelId || providerConfig?.modelId || 'unknown'}". Check the model id in Cloud Provider settings.`;
  } else if (error?.status >= 500) {
    code = 'server_error';
    message = `${providerName} returned a server error. Try again later or switch to another model.`;
  }

  const wrapped = new Error(message);
  wrapped.code = code;
  wrapped.status = error?.status;
  return wrapped;
}

// Map an internal message to the OpenAI chat shape. Most turns are plain
// role/content, but native function-calling turns carry assistant tool_calls
// and tool-result messages (role:'tool' + tool_call_id) that must be preserved.
function mapMessageForApi(item) {
  const role = item.role || 'user';
  if (role === 'tool') {
    return { role: 'tool', tool_call_id: item.tool_call_id, content: String(item.content ?? '') };
  }
  if (role === 'assistant' && Array.isArray(item.tool_calls) && item.tool_calls.length > 0) {
    return { role: 'assistant', content: item.content ? String(item.content) : null, tool_calls: item.tool_calls };
  }
  return { role, content: String(item.content ?? '') };
}

function parseOpenAIError(status, payload) {
  // Providers disagree on error shapes: some nest an object (error.message), some
  // (xAI, others) put a plain string in `error`. Handle both so the user sees the
  // server's actual reason instead of an opaque "HTTP 400".
  const serverMessage = typeof payload?.error === 'string'
    ? payload.error
    : payload?.error?.message || payload?.message;
  const error = new Error(serverMessage || `HTTP ${status}`);
  error.status = status;
  error.code = payload?.error?.code || payload?.code;
  return error;
}

export class OpenAICompatibleProvider {
  constructor(modelOrConfig = {}) {
    this.kind = 'cloud-openai-compatible';
    // Frontier cloud models (Groq/OpenAI/xAI/etc.) reliably follow tool-call
    // instructions, so the app can route them through the full agentic loop.
    this.supportsToolUse = true;
    this.modelOrConfig = modelOrConfig;
  }

  getConfig(model = this.modelOrConfig) {
    // A fully-specified connection object (has its own key + baseUrl) is used
    // directly — avoids a localStorage round-trip and works in failover where
    // the connection is passed in explicitly.
    if (model?.apiKey && model?.baseUrl && model?.modelId) return model;
    if (model?.connectionId) return getCloudProvider(model.connectionId) || model;
    if (model?.id) return getCloudProvider(model.id) || model;
    return model;
  }

  async getStatus() {
    const config = this.getConfig();
    return {
      connected: Boolean(config?.apiKey && config?.baseUrl && config?.modelId),
      available: Boolean(config?.apiKey && config?.baseUrl && config?.modelId),
      kind: this.kind,
      provider: config?.provider || 'custom',
    };
  }

  async loadModel(model) {
    const config = this.getConfig(model);
    if (!config?.apiKey) throw normalizeCloudError(new Error('Missing API key'), { providerConfig: config, model });
    if (!config?.baseUrl) throw new Error('Cloud provider base URL is required.');
    if (!model?.modelId && !config.modelId) throw new Error('Cloud model id is required.');
    return { loaded: true, cloud: true, provider: config.provider, modelId: model?.modelId || config.modelId };
  }

  async stream({ model, messages, signal, onToken, tools, toolChoice, maxTokens, maxRetries = 2, backoffMs = 800 }) {
    const config = this.getConfig(model);
    let lastError;
    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      let streamedAny = false;
      const guardedOnToken = (token) => { streamedAny = true; onToken?.(token); };
      try {
        return await this._streamOnce({ model, messages, signal, onToken: guardedOnToken, config, tools, toolChoice, maxTokens });
      } catch (error) {
        lastError = error;
        const normalized = normalizeCloudError(error, { providerConfig: config, model });
        // Never retry after tokens reached the UI (would duplicate output), on
        // user aborts, or on permanent errors (bad key / quota / missing model).
        const permanent = ['invalid_api_key', 'quota_exceeded', 'model_not_found', 'aborted'].includes(normalized.code);
        const retryable = !streamedAny && !permanent
          && (normalized.code === 'network_error' || normalized.code === 'rate_limited' || normalized.code === 'server_error');
        if (retryable && attempt < maxRetries && !signal?.aborted) {
          await new Promise(resolve => setTimeout(resolve, backoffMs * Math.pow(2, attempt)));
          continue;
        }
        throw normalized;
      }
    }
    throw normalizeCloudError(lastError, { providerConfig: config, model });
  }

  async _streamOnce({ model, messages, signal, onToken, config, tools, toolChoice, maxTokens }) {
    await this.loadModel(model);
    const body = {
      model: model?.modelId || config.modelId,
      // Preserve tool/assistant fields when present (native function-calling
      // turns), otherwise fall back to the plain role/content shape.
      messages: (messages || []).map(mapMessageForApi),
      stream: true,
    };
    if (Number.isFinite(maxTokens) && maxTokens > 0) body.max_tokens = Math.floor(maxTokens);
    if (Array.isArray(tools) && tools.length > 0) {
      body.tools = tools;
      body.tool_choice = toolChoice || 'auto';
    }
    const response = await fetch(`${config.baseUrl.replace(/\/$/, '')}/chat/completions`, {
      method: 'POST',
      signal,
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${config.apiKey}`,
        ...(config.provider === 'openrouter' ? { 'HTTP-Referer': 'https://luna.local', 'X-Title': 'Luna' } : {}),
      },
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      let payload = null;
      try { payload = await response.json(); } catch {}
      // Recovery: Groq/Llama sometimes emits a tool call in its own
      // `<function=NAME {json}>` syntax and then rejects its OWN output with
      // tool_use_failed. Salvage the intended tool call so the agent proceeds
      // instead of dead-ending (this is the "not in request.tools" error).
      const failedGen = payload?.error?.failed_generation;
      if (response.status === 400 && (payload?.error?.code === 'tool_use_failed' || /tool call/i.test(payload?.error?.message || ''))) {
        const recovered = failedGen ? parseLlamaFunctionSyntax(failedGen) : [];
        if (recovered.length > 0) {
          const toolCalls = recovered.map((c, i) => ({
            id: `recovered_${Date.now()}_${i}`,
            type: 'function',
            function: { name: c.tool, arguments: JSON.stringify(c.args) },
          }));
          return { cloud: true, provider: config.provider, modelId: model?.modelId || config.modelId, content: '', toolCalls, recovered: true };
        }
        // The model tried to call a tool that doesn't exist (e.g. invented a
        // name). Surface a soft, recoverable signal so the loop can correct it
        // rather than dead-ending the whole request.
        return {
          cloud: true, provider: config.provider, modelId: model?.modelId || config.modelId,
          content: '', toolError: (payload?.error?.message || 'invalid tool call'), failedGeneration: failedGen || '',
        };
      }
      throw parseOpenAIError(response.status, payload);
    }
    if (!response.body) throw new Error('Cloud provider did not return a stream.');

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let completion = '';
    let usage = null;
    const toolCallAcc = []; // accumulates streamed native tool_calls by index

    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const events = buffer.split('\n\n');
      buffer = events.pop() || '';
      for (const event of events) {
        for (const line of event.split('\n')) {
          const trimmed = line.trim();
          if (!trimmed.startsWith('data:')) continue;
          const data = trimmed.slice(5).trim();
          if (!data || data === '[DONE]') continue;
          // Individual SSE chunks can occasionally be split mid-JSON across
          // network reads, or be non-JSON keepalives. A parse failure on one
          // chunk must not abort (and needlessly retry) the whole stream.
          let chunk;
          try { chunk = JSON.parse(data); } catch { continue; }
          if (chunk.error) throw parseOpenAIError(response.status, chunk);
          usage = chunk.usage || usage;
          const delta = chunk.choices?.[0]?.delta || {};
          const token = delta.content || chunk.choices?.[0]?.text || '';
          if (token) {
            completion += token;
            onToken?.(token);
          }
          // Native tool_calls stream as deltas keyed by index; assemble them.
          if (Array.isArray(delta.tool_calls)) {
            for (const tc of delta.tool_calls) {
              const idx = tc.index ?? toolCallAcc.length;
              if (!toolCallAcc[idx]) toolCallAcc[idx] = { id: tc.id, type: 'function', function: { name: '', arguments: '' } };
              if (tc.id) toolCallAcc[idx].id = tc.id;
              if (tc.function?.name) toolCallAcc[idx].function.name += tc.function.name;
              if (tc.function?.arguments) toolCallAcc[idx].function.arguments += tc.function.arguments;
            }
          }
        }
      }
    }

    const toolCalls = toolCallAcc.filter(Boolean);
    return {
      cloud: true,
      provider: config.provider,
      modelId: model?.modelId || config.modelId,
      content: completion,
      usage,
      ...(toolCalls.length > 0 ? { toolCalls } : {}),
    };
  }

  async stop() { return { stopped: true, cloud: true }; }
  async unloadModel() { return { unloaded: true, cloud: true }; }
}

export function createModelProvider({ mode = 'ollama', endpoint } = {}) {
  return assertModelProvider(mode === 'on-device' ? new OnDeviceProvider() : new OllamaProvider(endpoint));
}

export function createModelProviderForModel(model, { endpoint, isNative = false } = {}) {
  if (model?.source === 'cloud' || model?.cloud) return assertModelProvider(new OpenAICompatibleProvider(model));
  return createModelProvider({ mode: isNative ? 'on-device' : 'ollama', endpoint });
}

/**
 * A cloud provider that automatically fails over to the next configured cloud
 * connection when the active one exhausts its quota / rate-limits / errors.
 * Drop-in: exposes the same stream() interface, so existing call sites (chat
 * path and agentic loop) get failover for free.
 *
 * @param {object} deps
 * @param {object} deps.activeModel  the selected cloud model object
 * @param {Function} deps.listProviders  () => cloud connection list
 * @param {Function} deps.isFailoverEnabled  () => boolean
 * @param {Function} deps.streamWithFailover  the failover runner
 * @param {Function} [deps.onFailover]  ({from,to,code}) => void  UI notice
 */
export function createFailoverCloudProvider({ activeModel, listProviders, isFailoverEnabled, streamWithFailover, onFailover }) {
  const single = new OpenAICompatibleProvider(activeModel);
  return assertModelProvider({
    kind: 'cloud-failover',
    supportsToolUse: true,
    getStatus: () => single.getStatus(),
    loadModel: (m) => single.loadModel(m || activeModel),
    stop: () => single.stop(),
    unloadModel: () => single.unloadModel(),
    async stream(args) {
      const providers = listProviders();
      // If only one provider is configured, this is just a normal stream.
      return streamWithFailover({
        providers,
        activeId: activeModel.connectionId,
        enabled: isFailoverEnabled(),
        // Construct the provider directly from the full connection object (which
        // carries apiKey/baseUrl/modelId) and pass that same object as the model,
        // so getConfig resolves it without needing a localStorage round-trip.
        makeProvider: conn => new OpenAICompatibleProvider(conn),
        buildModel: conn => conn,
        streamArgs: args,
        onFailover,
      });
    },
  });
}

export function assertModelProvider(provider) {
  const required = ['getStatus', 'loadModel', 'stream', 'stop', 'unloadModel'];
  for (const method of required) {
    if (typeof provider?.[method] !== 'function') throw new Error(`Invalid model provider: missing ${method}()`);
  }
  return provider;
}
