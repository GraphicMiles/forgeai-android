/**
 * useInference — model selection, provider construction, cloud providers, and
 * runtime status polling. One place that answers "what am I talking to?".
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { isNative } from '../nativeBridge.js';
import useModelCollection from './useModelCollection.js';
import { createModelProvider, createModelProviderForModel, createFailoverCloudProvider } from '../providers/modelProvider.js';
import { streamWithFailover } from '../providers/providerFailover.js';
import {
  cloudProviderToModel, cloudProvidersToModels, isFailoverEnabled,
  listCloudProviders, removeCloudProvider, saveCloudProvider,
} from '../providers/cloudProviderStore.js';

export default function useInference({ endpoint, onFailover }) {
  const collection = useModelCollection({ endpoint });
  const { models: downloadedModels, activeModel, setActiveModel } = collection;

  const [cloudProviders, setCloudProviders] = useState(() => listCloudProviders());
  const [ollamaConnected, setOllamaConnected] = useState(false);
  const [modelStatus, setModelStatus] = useState('off');
  const [isConnecting, setIsConnecting] = useState(true);
  const [runtimeInfo, setRuntimeInfo] = useState(null);
  const [lastBenchmark, setLastBenchmark] = useState(null);

  const cloudModels = useMemo(() => cloudProvidersToModels(cloudProviders), [cloudProviders]);
  const selectableModels = useMemo(() => [...downloadedModels, ...cloudModels], [downloadedModels, cloudModels]);

  // Drop the active model if its source disappeared.
  useEffect(() => {
    if (!activeModel) return;
    const isCloud = activeModel.source === 'cloud' || activeModel.cloud;
    const available = isCloud
      ? cloudProviders.some(p => p.id === activeModel.connectionId)
      : downloadedModels.some(m => m.id === activeModel.id);
    if (!available) setActiveModel(null);
  }, [activeModel, cloudProviders, downloadedModels, setActiveModel]);

  const runtimeProvider = useMemo(
    () => createModelProvider({ mode: isNative ? 'on-device' : 'ollama', endpoint }),
    [endpoint],
  );

  const provider = useMemo(() => {
    const isCloud = activeModel?.source === 'cloud' || activeModel?.cloud;
    if (isCloud) {
      return createFailoverCloudProvider({
        activeModel,
        listProviders: () => listCloudProviders(),
        isFailoverEnabled,
        streamWithFailover,
        onFailover,
      });
    }
    return createModelProviderForModel(activeModel, { endpoint, isNative });
  }, [activeModel, endpoint, onFailover]);

  const checkConnection = useCallback(async () => {
    try {
      const result = await runtimeProvider.getStatus();
      const available = Boolean(result.connected ?? result.available);
      setRuntimeInfo(result);
      setOllamaConnected(available);
      setModelStatus(current => current === 'busy' ? current : (available ? 'idle' : 'off'));
    } catch {
      setRuntimeInfo(null);
      setOllamaConnected(false);
      setModelStatus('off');
    } finally {
      setIsConnecting(false);
    }
  }, [runtimeProvider]);

  useEffect(() => {
    checkConnection();
    const interval = setInterval(checkConnection, 30000);
    return () => clearInterval(interval);
  }, [checkConnection]);

  const addCloudProvider = useCallback(config => {
    const saved = saveCloudProvider(config);
    setCloudProviders(listCloudProviders());
    return saved;
  }, []);

  const dropCloudProvider = useCallback(providerId => {
    setCloudProviders(removeCloudProvider(providerId));
    if (activeModel?.id === `cloud-model-${providerId}`) setActiveModel(null);
  }, [activeModel, setActiveModel]);

  const selectCloudProvider = useCallback(config => {
    setActiveModel(cloudProviderToModel(config));
    setModelStatus('idle');
  }, [setActiveModel]);

  return {
    ...collection,
    provider,
    selectableModels,
    cloudProviders,
    addCloudProvider,
    dropCloudProvider,
    selectCloudProvider,
    ollamaConnected,
    modelStatus,
    setModelStatus,
    isConnecting,
    runtimeInfo,
    setRuntimeInfo,
    lastBenchmark,
    setLastBenchmark,
  };
}
