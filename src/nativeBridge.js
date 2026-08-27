/**
 * Luna Native Bridge
 * Provides interface between web app and native Android/Desktop features
 * 
 * Supports Capacitor 7+ API
 */

import { Haptics } from '@capacitor/haptics';
import { App } from '@capacitor/app';
import { registerPlugin } from '@capacitor/core';
import { formatPrompt } from './models/promptProfiles.js';
import { getModelProfile } from './models/catalog.js';

const DeviceCapacity = registerPlugin('DeviceCapacity');
const OnDeviceRuntime = registerPlugin('OnDeviceRuntime');
const WorkspaceStorage = registerPlugin('WorkspaceStorage');
const TerminalRuntime = registerPlugin('TerminalRuntime');
const ResearchRuntime = registerPlugin('ResearchRuntime');
const CredentialVault = registerPlugin('CredentialVault');
const AutonomyRuntime = registerPlugin('AutonomyRuntime');
const GitRuntime = registerPlugin('GitRuntime');

export async function pickWorkspaceFolder() { return WorkspaceStorage.pickFolder(); }
export async function listWorkspace(uri, path = '') { return WorkspaceStorage.list({ uri, path }); }
export async function readWorkspaceFile(uri, path, maxBytes) { return (await WorkspaceStorage.readFile({ uri, path, maxBytes })).content; }
export async function writeWorkspaceFile(uri, path, content, maxBytes) { return WorkspaceStorage.writeFile({ uri, path, content, maxBytes }); }
export async function createWorkspaceFile(uri, path) { return WorkspaceStorage.createFile({ uri, path }); }
export async function createWorkspaceFolder(uri, path) { return WorkspaceStorage.createFolder({ uri, path }); }
export async function renameWorkspaceItem(uri, path, newName) { return WorkspaceStorage.rename({ uri, path, newName }); }
export async function deleteWorkspaceItem(uri, path, recoverable = true) { return WorkspaceStorage.delete({ uri, path, recoverable }); }
export async function inspectWorkspaceItem(uri, path) { return WorkspaceStorage.inspect({ uri, path }); }
export async function listWorkspaceBackups(uri) { return WorkspaceStorage.listBackups({ uri }); }
export async function restoreWorkspaceBackup(uri, backupId) { return WorkspaceStorage.restoreBackup({ uri, backupId }); }
export async function downloadModelToWorkspace(uri, url, path, sha256, onProgress) {
  const listener = await WorkspaceStorage.addListener('modelDownloadProgress', event => { if (event.path === path) onProgress?.(event); });
  try { return await WorkspaceStorage.download({ uri, url, path, sha256 }); } finally { await listener.remove(); }
}
export async function pauseWorkspaceModelDownload(uri, path) { return WorkspaceStorage.pauseDownload({ uri, path }); }
export async function cancelWorkspaceModelDownload(uri, path) { return WorkspaceStorage.cancelDownload({ uri, path }); }
export async function importModelToRuntime(uri, path) { return WorkspaceStorage.importToRuntime({ uri, path }); }
export async function pickModelFile() { return WorkspaceStorage.pickModelFile(); }
export async function importDocumentToRuntime(uri, name) { return WorkspaceStorage.importDocumentToRuntime({ uri, name }); }

export async function setFullAutonomy(enabled) { if (!isNative) throw new Error('Full autonomy requires Android.'); return AutonomyRuntime.setEnabled({ enabled }); }
export async function runTerminalCommand({ command, cwd = '', timeoutSeconds = 120, requestId }, onOutput) {
  if (!isNative) throw new Error('Terminal requires Android.');
  const listener = await TerminalRuntime.addListener('terminalOutput', event => { if (!requestId || event.requestId === requestId) onOutput?.(event.text); });
  try { return await TerminalRuntime.execute({ command, cwd, timeoutSeconds, requestId }); }
  finally { await listener.remove(); }
}
export async function searchOnline({ query, googleApiKey = '', googleCx = '' }) { if (!isNative) throw new Error('Native research requires Android.'); return ResearchRuntime.search({ query, googleApiKey, googleCx }); }
export async function fetchPublicUrl(url) { if (!isNative) throw new Error('Native research requires Android.'); return ResearchRuntime.fetchUrl({ url }); }
export async function storeGithubToken(token) { return CredentialVault.storeGithubToken({ token }); }
export async function hasGithubToken() { return CredentialVault.hasGithubToken(); }
export async function clearGithubToken() { return CredentialVault.clearGithubToken(); }
export async function gitClone(repository, branch = '') { return GitRuntime.cloneRepository({ repository, branch }); }
export async function gitStatus(path) { return GitRuntime.status({ path }); }
export async function gitLog(path, max = 20) { return GitRuntime.log({ path, max }); }
export async function gitCommit(path, message, authorName, authorEmail) { return GitRuntime.commit({ path, message, authorName, authorEmail }); }
export async function gitPush(path, force = false) { return GitRuntime.push({ path, force }); }

export async function getOnDeviceRuntimeInfo() {
  if (!isNative) return { available: false, reason: 'On-device runtime is available only in the Android build.' };
  try { return await OnDeviceRuntime.getInfo(); } catch { return { available: false, reason: 'Native inference runtime is not installed in this build.' }; }
}

export async function downloadOnDeviceModel(url, filename, sha256, onProgress) {
  if (!isNative) throw new Error('On-device model downloads require Android.');
  const listener = await OnDeviceRuntime.addListener('downloadProgress', event => {
    if (event.filename === filename) onProgress?.(event);
  });
  try { return await OnDeviceRuntime.download({ url, filename, sha256 }); }
  finally { await listener.remove(); }
}

export async function pauseOnDeviceDownload(filename) {
  if (isNative) return OnDeviceRuntime.pauseDownload({ filename });
}

export async function cancelOnDeviceDownload(filename) {
  if (isNative) return OnDeviceRuntime.cancelDownload({ filename });
}

export async function deleteOnDeviceModel(path) {
  if (isNative && path) return OnDeviceRuntime.deleteModel({ path });
}

export async function loadOnDeviceModel(model) {
  if (!isNative) throw new Error('On-device inference requires Android.');
  const path = typeof model === 'string' ? model : model?.localPath;
  const modelId = typeof model === 'string' ? model.split('/').pop() : model?.id;
  if (!path || !modelId) throw new Error('A downloaded model and runtime path are required.');
  return OnDeviceRuntime.load({ path, modelId });
}

export async function unloadOnDeviceModel() {
  if (isNative) return OnDeviceRuntime.unload();
}

export async function runOnDeviceChat({ model, messages, signal, onToken }) {
  if (!isNative) throw new Error('On-device inference requires Android.');
  if (!model?.id) throw new Error('A model manifest is required for on-device inference.');
  if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
  const profile = getModelProfile(model);
  const prompt = formatPrompt(messages, profile);
  const requestId = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random()}`;
  const tokenListener = await OnDeviceRuntime.addListener('generationToken', event => {
    if (event.requestId === requestId && event.modelId === model.id && event.token) onToken?.(event.token);
  });
  const cancel = () => OnDeviceRuntime.cancel({ requestId }).catch(() => {});
  signal?.addEventListener('abort', cancel, { once: true });
  try {
    const result = await OnDeviceRuntime.generate({
      prompt,
      modelId: model.id,
      maxTokens: profile.maxOutputTokens,
      contextTokens: profile.contextTokens,
      threads: profile.preferredThreads,
      requestId,
    });
    if (signal?.aborted || result?.cancelled) throw new DOMException('Aborted', 'AbortError');
    return result;
  } finally {
    signal?.removeEventListener('abort', cancel);
    await tokenListener.remove();
  }
}

// Check if running in Capacitor
export const isNative = typeof window !== 'undefined' && (
  window.Capacitor?.isNativePlatform?.() === true || window.Capacitor?.isNative === true
);

// Platform detection
const isAndroid = isNative && window.Capacitor.getPlatform() === 'android';

const asPositiveNumber = (value) => {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
};

/**
 * Read hardware capacity where the platform makes it available.
 *
 * Android uses the bundled DeviceCapacity plugin so the UI reads the phone's
 * real RAM and internal-storage capacity instead of relying on a fixed value.
 * Browsers deliberately restrict hardware details; in that case we expose the
 * browser's quota as such rather than presenting it as total device storage.
 */
export async function getDeviceCapacity() {
  const platform = isNative ? window.Capacitor.getPlatform() : 'web';

  if (isAndroid) {
    try {
      const capacity = await DeviceCapacity.getCapacity();
      const ramBytes = asPositiveNumber(capacity.totalRamBytes);
      return {
        ramBytes,
        availableRamBytes: asPositiveNumber(capacity.availableRamBytes),
        storageBytes: asPositiveNumber(capacity.totalStorageBytes),
        availableStorageBytes: asPositiveNumber(capacity.availableStorageBytes),
        // Model requirements are expressed in binary GB, matching Android RAM specs.
        ram: ramBytes ? Math.max(1, Math.round(ramBytes / (1024 ** 3))) : 4,
        storageScope: 'device',
        platform,
      };
    } catch (error) {
      console.warn('Unable to read Android device capacity:', error);
    }
  }

  const deviceMemory = typeof navigator !== 'undefined' ? navigator.deviceMemory : undefined;
  const ram = asPositiveNumber(deviceMemory) || 4;
  let storageBytes = null;

  try {
    if (navigator.storage?.estimate) {
      const estimate = await navigator.storage.estimate();
      storageBytes = asPositiveNumber(estimate.quota);
    }
  } catch {
    // Capacity data is optional in browsers.
  }

  return {
    ramBytes: asPositiveNumber(deviceMemory) ? deviceMemory * (1024 ** 3) : null,
    availableRamBytes: null,
    storageBytes,
    availableStorageBytes: null,
    ram,
    storageScope: storageBytes ? 'browser' : 'unknown',
    platform,
  };
}

/**
 * Haptic Feedback
 */
export const haptics = {
  /**
   * Light impact - for selections
   */
  async light() {
    if (isNative) {
      try {
        await Haptics.impact({ style: 'Light' });
      } catch {
        // Haptics not available
      }
    }
  },

  /**
   * Medium impact - for confirmations
   */
  async medium() {
    if (isNative) {
      try {
        await Haptics.impact({ style: 'Medium' });
      } catch {
        // Haptics not available
      }
    }
  },

  /**
   * Heavy impact - for warnings
   */
  async heavy() {
    if (isNative) {
      try {
        await Haptics.impact({ style: 'Heavy' });
      } catch {
        // Haptics not available
      }
    }
  },

  /**
   * Selection changed
   */
  async selection() {
    if (isNative) {
      try {
        await Haptics.selectionChanged();
      } catch {
        // Haptics not available
      }
    }
  },

  /**
   * Success notification
   */
  async success() {
    if (isNative) {
      try {
        await Haptics.notification({ type: 'Success' });
      } catch {
        // Haptics not available
      }
    }
  },

  /**
   * Error notification
   */
  async error() {
    if (isNative) {
      try {
        await Haptics.notification({ type: 'Error' });
      } catch {
        // Haptics not available
      }
    }
  },
};

/**
 * App Lifecycle
 */
export const app = {
  /**
   * Get app info
   */
  async getInfo() {
    if (isNative) {
      return await App.getInfo();
    }
    return {
      name: 'Luna Web',
      id: 'ai.luna.web',
      version: '0.0.1',
    };
  },

  /**
   * Handle app state change
   */
  onStateChange(callback) {
    if (isNative) {
      App.addListener('appStateChange', callback);
      return () => App.removeListener('appStateChange', callback);
    }
    return () => {};
  },

  /**
   * Handle deep link
   */
  onDeepLink(callback) {
    if (isNative) {
      App.addListener('deepLink', callback);
      return () => App.removeListener('deepLink', callback);
    }
    return () => {};
  },
};

/**
 * Ollama Connection Check
 * Checks if Ollama is running locally
 */
export async function streamOllamaChat({ url = 'http://localhost:11434', model, messages, signal, onToken }) {
  const response = await fetch(`${url.replace(/\/$/, '')}/api/chat`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ model, messages, stream: true }), signal });
  if (!response.ok) throw new Error(`Ollama chat failed (${response.status})`);
  if (!response.body) throw new Error('Runtime did not return a stream');
  const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = '';
  while (true) { const { value, done } = await reader.read(); if (done) break; buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n'); buffer = lines.pop() || '';
    for (const line of lines) { if (!line.trim()) continue; const chunk = JSON.parse(line); if (chunk.error) throw new Error(chunk.error); if (chunk.message?.content) onToken?.(chunk.message.content); if (chunk.done) return chunk; }
  }
}

export async function pullOllamaModel(model, url = 'http://localhost:11434', onProgress, signal) {
  const response = await fetch(`${url.replace(/\/$/, '')}/api/pull`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: model, stream: true }), signal });
  if (!response.ok) throw new Error(`Model download failed (${response.status})`);
  const reader = response.body?.getReader(); if (!reader) throw new Error('Download stream unavailable'); const decoder = new TextDecoder(); let buffer = ''; let last;
  while (true) { const { value, done } = await reader.read(); if (done) break; buffer += decoder.decode(value, { stream: true }); const lines = buffer.split('\n'); buffer = lines.pop() || '';
    for (const line of lines) { if (!line.trim()) continue; const item = JSON.parse(line); last = item; const total = Number(item.total) || 0; const completed = Number(item.completed) || 0; onProgress?.({ status: item.status || 'downloading', progress: total ? Math.round(completed / total * 100) : 0, completed, total, speed: 0 }); if (item.error) throw new Error(item.error); }
  } return last || {};
}

export async function deleteOllamaModel(model, url = 'http://localhost:11434') {
  const response = await fetch(`${url.replace(/\/$/, '')}/api/delete`, { method: 'DELETE', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: model }) });
  if (!response.ok && response.status !== 404) throw new Error(`Model deletion failed (${response.status})`);
}

export async function checkOllamaConnection(url = 'http://localhost:11434') {
  try {
    const response = await fetch(`${url}/api/tags`, {
      method: 'GET',
      signal: AbortSignal.timeout(3000),
    });
    if (response.ok) {
      const data = await response.json();
      return { connected: true, models: data.models || [] };
    }
    return { connected: false, models: [] };
  } catch {
    return { connected: false, models: [] };
  }
}

