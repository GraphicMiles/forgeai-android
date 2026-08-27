import { useState, useEffect, useCallback, useRef } from 'react';
import { createModelManifest } from '../models/modelManifest.js';
import { getModelBySha256 } from '../models/catalog.js';
import {
  checkOllamaConnection, pullOllamaModel, deleteOllamaModel,
  downloadOnDeviceModel, pauseOnDeviceDownload, cancelOnDeviceDownload, deleteOnDeviceModel, loadOnDeviceModel, unloadOnDeviceModel, isNative, downloadModelToWorkspace, pauseWorkspaceModelDownload, cancelWorkspaceModelDownload, importModelToRuntime, pickModelFile, importDocumentToRuntime, deleteWorkspaceItem, listWorkspace
} from '../nativeBridge';

const STORAGE_KEY = 'luna_models';
const ACTIVE_MODEL_KEY = 'luna_active_model';
const read = (key, fallback) => { try { const v = JSON.parse(localStorage.getItem(key)); return v ?? fallback; } catch { return fallback; } };
const readModels = () => { const v = read(STORAGE_KEY, []); return Array.isArray(v) ? v.filter(i => i && typeof i.id === 'string') : []; };
const readActive = () => { const v = read(ACTIVE_MODEL_KEY, null); return v && typeof v.id === 'string' ? v : null; };

export default function useModelCollection({ endpoint = 'http://localhost:11434' } = {}) {
  const [models, setModels] = useState(readModels);
  const [activeModel, setActiveModelState] = useState(readActive);
  const [isLoading, setIsLoading] = useState(true);
  const [downloads, setDownloads] = useState({});
  const controllers = useRef(new Map());
  const downloadFiles = useRef(new Map());

  useEffect(() => { setIsLoading(false); }, []);


  const saveModels = useCallback((nextOrUpdater) => {
    setModels(prev => {
      const next = typeof nextOrUpdater === 'function' ? nextOrUpdater(prev) : nextOrUpdater;
      try { localStorage.setItem(STORAGE_KEY, JSON.stringify(next)); } catch (error) { console.warn('Unable to persist model metadata:', error); }
      return next;
    });
  }, []);

  // Upgrade manually imported official files when their recorded hash matches the signed catalog metadata.
  useEffect(() => {
    saveModels(previous => {
      const upgraded = previous.map(model => {
        const catalog = getModelBySha256(model.sha256);
        if (!catalog) return model;
        return {
          ...model,
          ...catalog,
          id: catalog.id,
          localPath: model.localPath || model.runtimePath,
          runtimePath: model.runtimePath || model.localPath,
          sourceUri: model.sourceUri || null,
          sourcePath: model.sourcePath || null,
          downloadedBytes: model.downloadedBytes || catalog.sizeBytes,
          verified: true,
          integrity: 'publisher-verified',
        };
      });
      return [...new Map(upgraded.map(model => [model.id, model])).values()];
    });
    setActiveModelState(previous => {
      if (!previous) return previous;
      const catalog = getModelBySha256(previous.sha256);
      if (!catalog) return previous;
      const upgraded = { ...previous, ...catalog, id: catalog.id, localPath: previous.localPath || previous.runtimePath, verified: true, integrity: 'publisher-verified' };
      try { localStorage.setItem(ACTIVE_MODEL_KEY, JSON.stringify(upgraded)); }
      catch (error) { console.warn('Unable to persist active model metadata:', error); }
      return upgraded;
    });
  }, [saveModels]);

  // Re-discover durable SAF models after reinstall or APK replacement.
  useEffect(() => {
    if (!isNative) return;
    const uri = localStorage.getItem('luna_model_folder_uri');
    if (!uri?.startsWith('content://')) return;
    let cancelled = false;
    const flatten = (nodes, out = []) => { for (const node of nodes || []) { if (node.type === 'folder') flatten(node.children, out); else if (node.path?.toLowerCase().endsWith('.gguf')) out.push(node.path); } return out; };
    (async () => {
      try {
        const treeResult = await listWorkspace(uri);
        const paths = flatten(treeResult?.children || treeResult?.value || treeResult || []);
        for (const path of paths) {
          if (cancelled || models.some(model => model.sourcePath === path || model.file === path.split('/').pop())) continue;
          try {
            const imported = await importModelToRuntime(uri, path);
            const catalog = getModelBySha256(imported.sha256);
            const fallback = { id: `imported-${path.split('/').pop().replace(/[^a-z0-9]+/gi, '-').toLowerCase()}`, name: path.split('/').pop(), file: path.split('/').pop() };
            const manifest = createModelManifest(catalog || fallback, { runtimePath: imported.runtimePath, sourcePath: path, sourceUri: uri, sha256: imported.sha256, verified: Boolean(catalog), sizeBytes: imported.size });
            saveModels(prev => prev.some(model => model.id === manifest.id) ? prev : [...prev, manifest]);
          } catch (error) { console.warn('Durable model import skipped:', path, error); }
        }
      } catch (error) { console.warn('Durable model scan failed:', error); }
    })();
    return () => { cancelled = true; };
  }, [models, saveModels]);


  const downloadModel = useCallback(async (model, externalOnProgress) => {
    if (models.some(m => m.id === model.id)) return { success: false, error: 'Model already downloaded' };

    const name = model.ollamaName || model.id;
    setDownloads(d => ({ ...d, [model.id]: { status: 'downloading', progress: 0 } }));
    const controller = new AbortController();
    controllers.current.set(model.id, controller);
    downloadFiles.current.set(model.id, model.file || `${model.id}.gguf`);

    // Internal progress handler that always updates downloads state
    const trackProgress = (p) => {
      setDownloads(d => ({ ...d, [model.id]: { ...d[model.id], ...p } }));
      externalOnProgress?.(p);
    };

    try {
      let result;
      if (isNative && model.downloadUrl) {
        // Android models must use an explicitly selected durable folder.
        const modelFolderUri = localStorage.getItem('luna_model_folder_uri') || '';
        if (!modelFolderUri.startsWith('content://')) throw new Error('Choose a model folder before downloading on Android.');
        if (modelFolderUri.startsWith('content://')) {
          const durablePath = model.file || `${model.id}.gguf`;
          await trackProgress({ status: 'downloading', progress: 0 });
          const durable = await downloadModelToWorkspace(modelFolderUri, model.downloadUrl, durablePath, model.sha256, trackProgress);
          if (durable?.paused) return { success: false, paused: true, completed: durable.completed, total: durable.total };
          const imported = await importModelToRuntime(modelFolderUri, durablePath);
          result = { ...durable, ...imported, sourceUri: modelFolderUri, durablePath };
          trackProgress({ status: 'completed', progress: 100, completed: result.size || 0, total: result.size || 0 });
        } else {
          result = await downloadOnDeviceModel(model.downloadUrl, model.file || `${model.id}.gguf`, model.sha256, trackProgress);
        }
        trackProgress({ status: 'completed', progress: 100, completed: result.size || 0, total: result.size || 0 });
      } else {
        // Ollama pull (web/desktop)
        result = await pullOllamaModel(name, endpoint, trackProgress, controller.signal);
      }

      // Check if the result indicates a pause
      if (result && result.paused) {
        setDownloads(d => ({ ...d, [model.id]: { status: 'paused', completed: result.size || 0 } }));
        return { success: false, paused: true };
      }

      if (isNative && model.sha256 && result.sha256?.toLowerCase() !== model.sha256.toLowerCase()) {
        throw new Error('Downloaded model checksum does not match the catalog manifest.');
      }

      const publisherVerified = Boolean(isNative && model.sha256 && result.sha256);
      const installed = isNative
        ? createModelManifest(model, {
            runtimePath: result.runtimePath || result.path,
            sourceUri: result.sourceUri || null,
            sourcePath: result.durablePath || null,
            sha256: result.sha256 || null,
            verified: publisherVerified,
            sizeBytes: result.total || result.size || model.sizeBytes,
          })
        : { ...model, ollamaName: name, status: 'ready', downloadedAt: new Date().toISOString(), verified: false, integrity: 'ollama-managed' };
      saveModels(prev => prev.some(i => i.id === model.id) ? prev : [...prev, installed]);
      controllers.current.delete(model.id);
      setDownloads(d => { const n = { ...d }; delete n[model.id]; return n; });
      return { success: true, model: installed };
    } catch (error) {
      // Guard: if a newer download replaced this one, don't touch state
      if (controllers.current.get(model.id) !== controller) {
        return { success: false, error: error.message };
      }
      controllers.current.delete(model.id);
      const isAbort = error.name === 'AbortError' || error.message?.includes('aborted');
      setDownloads(d => {
        if (!d[model.id]) return d; // already cleared by cancel
        return { ...d, [model.id]: { status: isAbort ? 'cancelled' : 'failed', error: error.message } };
      });
      return { success: false, error: error.message };
    }
  }, [models, saveModels, endpoint]);

  const pauseDownload = useCallback(async (model) => {
    if (isNative) {
      const uri = localStorage.getItem('luna_model_folder_uri') || '';
      const result = uri.startsWith('content://')
        ? await pauseWorkspaceModelDownload(uri, model.file || `${model.id}.gguf`)
        : await pauseOnDeviceDownload(model.file || `${model.id}.gguf`);
      setDownloads(d => ({ ...d, [model.id]: { ...d[model.id], status: 'paused' } }));
      return result;
    }
    controllers.current.get(model.id)?.abort();
    setDownloads(d => ({ ...d, [model.id]: { ...d[model.id], status: 'paused' } }));
    return { paused: true };
  }, []);

  const cancelDownload = useCallback(async (modelId) => {
    // Abort the JS request and the native download thread
    controllers.current.get(modelId)?.abort();
    if (isNative) { const uri = localStorage.getItem('luna_model_folder_uri') || ''; const filename = downloadFiles.current.get(modelId) || `${modelId}.gguf`; if (uri.startsWith('content://')) await cancelWorkspaceModelDownload(uri, filename).catch(() => {}); else await cancelOnDeviceDownload(filename).catch(() => {}); }
    controllers.current.delete(modelId);
    downloadFiles.current.delete(modelId);
    // Remove from downloads entirely so it can be retried immediately
    setDownloads(d => { const n = { ...d }; delete n[modelId]; return n; });
  }, []);

  const retryDownload = useCallback((model, onProgress) => {
    // Clear any stale entry before retrying
    setDownloads(d => { const n = { ...d }; delete n[model.id]; return n; });
    return downloadModel(model, onProgress);
  }, [downloadModel]);

  const deleteModel = useCallback(async (modelId) => {
    const model = models.find(item => item.id === modelId);
    if (!model) return { success: false, error: 'Model is not in the collection.' };
    try {
      if (isNative && activeModel?.id === modelId) await unloadOnDeviceModel();
      // Remove the durable source first so the discovery scan cannot immediately re-import it.
      if (isNative && model.sourceUri && model.sourcePath) await deleteWorkspaceItem(model.sourceUri, model.sourcePath, false);
      if (isNative && model.localPath) await deleteOnDeviceModel(model.localPath);
      else if (!isNative) await deleteOllamaModel(model.ollamaName || model.id, endpoint);
    } catch (error) {
      console.warn('Model delete failed', error);
      return { success: false, error: error.message || 'Model deletion failed.' };
    }
    if (activeModel?.id === modelId) {
      setActiveModelState(null);
      localStorage.removeItem(ACTIVE_MODEL_KEY);
    }
    saveModels(models.filter(item => item.id !== modelId));
    return { success: true };
  }, [models, activeModel, saveModels, endpoint]);

  const setActiveModel = useCallback((model) => {
    setActiveModelState(model);
    try {
      if (model) localStorage.setItem(ACTIVE_MODEL_KEY, JSON.stringify(model));
      else localStorage.removeItem(ACTIVE_MODEL_KEY);
    } catch (error) {
      console.warn('Unable to persist active model:', error);
    }
  }, []);

  const mountModel = useCallback(async (model) => {
    if (isNative && model.localPath) {
      try { await loadOnDeviceModel(model); }
      catch (error) { return { success: false, error: error.message }; }
    }
    setActiveModel(model);
    return { success: true, mounted: isNative };
  }, [setActiveModel]);

  const unmountModel = useCallback(async () => {
    if (isNative) await unloadOnDeviceModel().catch(() => {});
    setActiveModel(null);
    return { success: true, message: 'Model unloaded' };
  }, [setActiveModel]);

  const importModel = useCallback(async () => {
    if (!isNative) throw new Error('Model import requires Android.');
    const selected = await pickModelFile();
    if (!selected?.uri) return { success: false, cancelled: true };
    const imported = await importDocumentToRuntime(selected.uri, selected.name);
    const catalog = getModelBySha256(imported.sha256);
    const fallback = { id: `imported-${selected.name.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}`, name: selected.name, file: selected.name };
    const model = createModelManifest(catalog || fallback, { runtimePath: imported.runtimePath, sourceUri: selected.uri, sha256: imported.sha256, verified: Boolean(catalog), sizeBytes: imported.size });
    saveModels(prev => prev.some(item => item.id === model.id) ? prev : [...prev, model]);
    return { success: true, model };
  }, [saveModels]);

  const stopModel = useCallback(async () => {
    if (isNative) {
      await unloadOnDeviceModel().catch(() => {});
    }
    setActiveModel(null);
    return { success: true, message: 'Model stopped and unloaded' };
  }, [setActiveModel]);
  const isDownloaded = useCallback((id) => models.some(m => m.id === id), [models]);

  return {
    models, activeModel, isLoading, downloads,
    downloadModel, retryDownload, cancelDownload, pauseDownload,
    deleteModel, setActiveModel, stopModel, isDownloaded, importModel,
    mountModel, unmountModel,
    refresh: async () => checkOllamaConnection(endpoint),
  };
}
