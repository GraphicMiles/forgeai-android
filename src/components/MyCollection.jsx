import { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import {
  Check, Trash2, Pause, MessageSquare,
  ChevronDown, Wifi, WifiOff, Database, RefreshCw,
  Plus, MoreVertical, ArrowUpRight
} from 'lucide-react';
import { App as CapacitorApp } from '@capacitor/app';
import { isNative } from '../nativeBridge.js';

function openExternal(url) {
  if (!url) return;
  if (isNative) { CapacitorApp.openUrl({ url }).catch(() => {}); return; }
  window.open(url, '_blank', 'noopener,noreferrer');
}
import { CLOUD_PROVIDER_PRESETS, getCloudProviderPreset, KNOWN_KEY_PREFIXES, fetchProviderModels } from '../providers/cloudProviderStore.js';
import { formatModelSize, formatStorageCapacity, getModelSizeBytes } from '../utils/deviceCapacity';
import DropdownMenu from './DropdownMenu.jsx';
import './MyCollection.css';

function CloudProviderPanel({ providers = [], onAdd, onRemove, onSelectModel }) {
  const [provider, setProvider] = useState('openai');
  const preset = getCloudProviderPreset(provider);
  const [label, setLabel] = useState(preset.label);
  const [baseUrl, setBaseUrl] = useState(preset.baseUrl);
  const [modelId, setModelId] = useState(preset.defaultModel);
  // When true, the user is typing a model id that isn't in the preset list.
  const [customModel, setCustomModel] = useState(false);
  const [apiKey, setApiKey] = useState('');
  const [error, setError] = useState('');
  // Live model ids fetched from the provider's /models endpoint (override the
  // curated preset list when present — presets drift per account/provider).
  const [fetchedModels, setFetchedModels] = useState(null);
  const [fetchingModels, setFetchingModels] = useState(false);
  // The add-provider form only renders on demand — the empty state and the form
  // are never on screen at the same time, and Save stays anchored inside the form card.
  const [formOpen, setFormOpen] = useState(false);

  const changeProvider = (nextProvider) => {
    const next = getCloudProviderPreset(nextProvider);
    setProvider(nextProvider);
    setLabel(next.label);
    setBaseUrl(next.baseUrl);
    setModelId(next.defaultModel);
    // Custom-endpoint provider (or one with no curated list) uses free text.
    setCustomModel(!next.models || next.models.length === 0);
    setFetchedModels(null); // reset live list when switching providers
    setError('');
  };

  // Model options: prefer LIVE models fetched from the provider (accurate for
  // this account) over the curated preset list (a drifting starting point), then
  // a "Custom model ID…" escape hatch. Ensures the current modelId is shown.
  const CUSTOM_OPTION = '__custom__';
  const availableModels = fetchedModels && fetchedModels.length ? fetchedModels : (Array.isArray(preset.models) ? preset.models : []);
  const modelOptions = (() => {
    const list = [...availableModels];
    if (modelId && !list.includes(modelId) && !customModel) list.unshift(modelId);
    return [
      ...list.map(m => ({ value: m, label: m })),
      { value: CUSTOM_OPTION, label: 'Custom model ID…' },
    ];
  })();

  const fetchModels = async () => {
    setFetchingModels(true);
    setError('');
    try {
      const ids = await fetchProviderModels({ baseUrl, apiKey });
      setFetchedModels(ids);
      setCustomModel(false);
      // Keep the current model if the provider still offers it; else pick the first.
      if (!ids.includes(modelId)) setModelId(ids[0]);
    } catch (err) {
      setError(err.message || 'Could not fetch models.');
    } finally {
      setFetchingModels(false);
    }
  };

  // Well-known key prefixes (from the catalog) — a soft, non-blocking hint when
  // the pasted key clearly belongs to a different provider. Longest prefix first
  // so "sk-or-" (OpenRouter) wins over "sk-" (OpenAI).
  const keyOwner = [...KNOWN_KEY_PREFIXES]
    .sort((a, b) => b.prefix.length - a.prefix.length)
    .find(item => apiKey.trim().startsWith(item.prefix));
  const keyMismatch = keyOwner && keyOwner.provider !== provider ? keyOwner : null;

  const submit = (event) => {
    event.preventDefault();
    setError('');
    try {
      onAdd?.({ provider, label, baseUrl, modelId, apiKey });
      setApiKey('');
      setFormOpen(false);
    } catch (err) {
      setError(err.message || 'Could not save cloud provider.');
    }
  };

  const formCard = (
    <form className="model-item-details expanded provider-form" onSubmit={submit}>
      <div className="cloud-provider-copy">
        <span className="active-label">Add cloud provider</span>
        <p>
          Add an OpenAI-compatible cloud endpoint. Cloud models use provider API quota; local GGUF models do not.
        </p>
      </div>
      <div className="details-grid">
        <label className="detail">
          <span className="detail-label">Provider</span>
          <DropdownMenu
            value={provider}
            onChange={changeProvider}
            label="Provider"
            options={CLOUD_PROVIDER_PRESETS.map(item => ({ value: item.id, label: item.label }))}
          />
        </label>
        <label className="detail">
          <span className="detail-label">Display name</span>
          <input value={label} onChange={event => setLabel(event.target.value)} placeholder="Grok" />
        </label>
        <label className="detail">
          <span className="detail-label">API key</span>
          <input type="password" value={apiKey} onChange={event => setApiKey(event.target.value)} placeholder="Provider API key" />
        </label>
        <label className="detail">
          <span className="detail-label">Base URL</span>
          <input value={baseUrl} onChange={event => setBaseUrl(event.target.value)} placeholder="https://api.example.com/v1" />
        </label>
        <label className="detail">
          <span className="detail-label">Model</span>
          {availableModels.length > 0 && !customModel ? (
            <DropdownMenu
              value={modelId || availableModels[0]}
              onChange={(val) => { if (val === CUSTOM_OPTION) { setCustomModel(true); setModelId(''); } else { setModelId(val); } }}
              label="Model"
              options={modelOptions}
            />
          ) : (
            <input value={modelId} onChange={event => setModelId(event.target.value)} placeholder="model id (e.g. gpt-oss-120b)" />
          )}
        </label>
      </div>
      <p className="setting-help provider-model-hint">
        {fetchedModels
          ? `Showing ${fetchedModels.length} live model(s) from this provider/key.`
          : customModel
            ? 'Enter any model id this provider supports.'
            : 'Preset list is a starting point — click "Fetch models" for the exact ids your key can use.'}
        {'  '}
        <button type="button" className="link-button" onClick={fetchModels} disabled={fetchingModels}>
          {fetchingModels ? 'Fetching…' : 'Fetch models'}
        </button>
        {customModel && availableModels.length > 0 && (
          <button type="button" className="link-button" onClick={() => { setCustomModel(false); setModelId(availableModels[0]); }}> Use list</button>
        )}
      </p>
      {preset.id !== 'custom' && (
        <div className="provider-howto">
          {preset.freeTier && <p className="setting-help"><strong>Free tier:</strong> {preset.freeTier}</p>}
          {preset.howTo && <p className="setting-help"><strong>How to get a key:</strong> {preset.howTo}</p>}
          <p className="setting-help provider-howto-links">
            {preset.keyUrl && (
              <button type="button" className="link-button" onClick={() => openExternal(preset.keyUrl)}>
                <ArrowUpRight size={12} /> Get API key
              </button>
            )}
            {preset.docs && (
              <button type="button" className="link-button" onClick={() => openExternal(preset.docs)}>
                <ArrowUpRight size={12} /> Docs
              </button>
            )}
            {preset.card && <span className="provider-card-note">💳 Card may be required</span>}
          </p>
        </div>
      )}
      {keyMismatch && (
        <p className="setting-help provider-key-warning">
          This looks like a {keyMismatch.label} key, but the selected preset is {preset.label}. Switch the provider preset or check the key.
        </p>
      )}
      {error && <p className="setting-help error-text">{error}</p>}
      <div className="details-actions">
        <button className="btn-select" type="submit"><Check size={14} /> Save Provider</button>
        {providers.length > 0 && (
          <div className="details-actions-secondary">
            <button type="button" className="btn-deselect" onClick={() => { setFormOpen(false); setError(''); }}>Cancel</button>
          </div>
        )}
      </div>
    </form>
  );

  return (
    <div className="model-list">
      {providers.length === 0 ? (
        formOpen ? formCard : (
          <div className="empty-collection">
            <div className="empty-icon"><Wifi size={32} /></div>
            <h3>No cloud providers connected</h3>
            <p>Connect an OpenAI-compatible endpoint to use cloud models alongside local GGUF models.</p>
            <button type="button" className="btn-open-zoo" onClick={() => setFormOpen(true)}>
              <Plus size={14} /> Add cloud provider
            </button>
          </div>
        )
      ) : (
        <>
          {providers.map(item => {
            const providerPreset = getCloudProviderPreset(item.provider);
            return (
              <div className="model-item" key={item.id}>
                <div className="model-item-main" onClick={() => onSelectModel?.(item)}>
                  <div className="model-item-left">
                    <div className="model-radio"><span className="radio-inactive" /></div>
                    <div className="model-item-info">
                      <span className="model-item-name">{item.label}</span>
                      <span className="model-item-size mono">{providerPreset.label} · {item.modelId}</span>
                    </div>
                  </div>
                  <div className="model-item-right">
                    <span className="running-badge">Cloud</span>
                  </div>
                </div>
                <div className="model-item-details expanded">
                  <div className="details-grid">
                    <div className="detail"><span className="detail-label">Base URL</span><span className="detail-value mono">{item.baseUrl}</span></div>
                    <div className="detail"><span className="detail-label">Quota</span><span className="detail-value">Provider token/API limits apply</span></div>
                  </div>
                  <div className="details-actions">
                    <button className="btn-select" onClick={() => onSelectModel?.(item)}><MessageSquare size={14} /> Select & Chat</button>
                    <div className="details-actions-secondary">
                      <button className="btn-delete" onClick={() => onRemove?.(item.id)}><Trash2 size={14} /> Remove</button>
                    </div>
                  </div>
                </div>
              </div>
            );
          })}
          {formOpen ? formCard : (
            <button type="button" className="collection-import add-provider-btn" onClick={() => setFormOpen(true)}>
              <Plus size={14} /> Add cloud provider
            </button>
          )}
        </>
      )}
    </div>
  );
}

export default function MyCollection({ 
  models = [], 
  activeModel,
  onSelect,
  onDelete,
  onStop,
  isRunning = false,
  ollamaConnected = false,
  runtimeMode,
  runtimeInfo = null,
  benchmark = null,
  deviceCapability = {},
  onOpenZoo,
  onImportModel,
  onRefreshDevice,
  onMountModel,
  onUnmountModel,
  isNative = false,
  cloudProviders = [],
  onAddCloudProvider,
  onRemoveCloudProvider,
  onSelectCloudModel,
}) {
  const [providerTab, setProviderTab] = useState('local');
  const [expandedId, setExpandedId] = useState(null);
  const [actionsOpen, setActionsOpen] = useState(false);
  // Power-user metadata (hashes, revision, license) hidden behind this toggle inside the expanded card.
  const [techDetailsOpen, setTechDetailsOpen] = useState(false);

  const usedStorageBytes = models.reduce(
    (total, model) => total + getModelSizeBytes(model),
    0,
  );
  const storageSummary = deviceCapability.storageBytes
    ? `Using ${formatModelSize(usedStorageBytes)} of ${formatStorageCapacity(deviceCapability.storageBytes)}`
    : `Using ${formatModelSize(usedStorageBytes)}`;

  const formatBytes = bytes => bytes ? formatModelSize(bytes) : 'Catalog estimate';

  const formatDate = (date) => {
    if (!date) return '';
    const d = new Date(date);
    return d.toLocaleDateString();
  };

  // Tapping the model row selects it + navigates to chat
  const handleTap = (model) => {
    onSelect?.(model);
  };

  // Chevron toggles details expand/collapse
  const toggleExpand = (e, modelId) => {
    e.stopPropagation();
    setExpandedId(prev => prev === modelId ? null : modelId);
    setTechDetailsOpen(false);
  };

  return (
    <div className="my-collection" onClick={() => setActionsOpen(false)}>
      <div className="collection-header">
        <div className="collection-title-main">
          <div className="collection-heading">
            <h2 className="display">My Collection</h2>
            {models.length > 0 && <span className="model-count">{models.length}</span>}
          </div>
          <div className={`ollama-status ${ollamaConnected ? 'connected' : 'disconnected'}`}>
            {ollamaConnected ? <Wifi size={14} /> : <WifiOff size={14} />}
            <span>{runtimeMode || (ollamaConnected ? 'Ollama active' : 'Offline')}</span>
          </div>
        </div>

        {/* One primary action + overflow (Raw Mode / Create Profile), same pattern as the Files toolbar */}
        <div className="collection-actions" aria-label="Collection actions">
          {isNative ? (
            <button className="collection-import primary" onClick={onImportModel}>
              <Plus size={16} /> Import GGUF
            </button>
          ) : (
            <span className="collection-hint">Add a cloud provider or import a GGUF.</span>
          )}
          <div className="collection-menu-anchor">
            <button
              type="button"
              className="collection-menu-btn"
              onClick={(event) => { event.stopPropagation(); setActionsOpen(value => !value); }}
              aria-label="More collection actions"
              aria-expanded={actionsOpen}
              title="More actions"
            >
              <MoreVertical size={16} />
            </button>
            {actionsOpen && (
              <div className="collection-menu" onClick={(event) => event.stopPropagation()}>
              </div>
            )}
          </div>
        </div>
      </div>

      <div className="collection-provider-tabs" role="tablist" aria-label="Provider source">
        <button
          type="button"
          role="tab"
          aria-selected={providerTab === 'local'}
          className={`provider-tab ${providerTab === 'local' ? 'active' : ''}`}
          onClick={() => setProviderTab('local')}
        >
          Local
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={providerTab === 'cloud'}
          className={`provider-tab ${providerTab === 'cloud' ? 'active' : ''}`}
          onClick={() => setProviderTab('cloud')}
        >
          Cloud
        </button>
      </div>

      {providerTab === 'cloud' ? (
        <CloudProviderPanel
          providers={cloudProviders}
          onAdd={onAddCloudProvider}
          onRemove={onRemoveCloudProvider}
          onSelectModel={onSelectCloudModel}
        />
      ) : (
        <>

      {/* Active Model Banner */}
      {activeModel && (
        <motion.div 
          className="active-model-banner"
          initial={{ opacity: 0, y: -10 }}
          animate={{ opacity: 1, y: 0 }}
        >
          <div className="active-model-info">
            <div className="active-indicator">
              {isRunning ? (
                <motion.span 
                  className="pulse"
                  animate={{ scale: [1, 1.2, 1] }}
                  transition={{ repeat: Infinity, duration: 1.5 }}
                />
              ) : (
                <span className="static" />
              )}
            </div>
            <div>
              <span className="active-label">Active Model</span>
              <span className="active-name">{activeModel.name}</span>
            </div>
          </div>
          <div className="active-model-actions">
            <button 
              className="btn-chat"
              onClick={() => onSelect?.(activeModel)}
            >
              <MessageSquare size={14} />
              Chat
            </button>
            {isRunning ? (
              <button 
                className="btn-stop"
                onClick={() => onStop?.()}
              >
                <Pause size={14} />
                Stop
              </button>
            ) : null}
          </div>
        </motion.div>
      )}

      {isNative && benchmark && benchmark.modelId === activeModel?.id && (
        <section className="active-model-banner" aria-label="Last on-device benchmark">
          <div className="active-model-info">
            <Database size={18} />
            <div>
              <span className="active-label">Last benchmark</span>
              <span className="active-name">{benchmark.tokensPerSecond?.toFixed(1) || '0.0'} tok/s</span>
            </div>
          </div>
          <div className="mono benchmark-meta">
            <div>Load {Math.round(benchmark.loadMs || 0)} ms{benchmark.loadReused ? ' (cached)' : ''}</div>
            <div>Prefill {benchmark.prefillTokensPerSecond?.toFixed(1) || '0.0'} tok/s</div>
            <div>{benchmark.contextTokens} ctx · {benchmark.threads} threads · {benchmark.abi || runtimeInfo?.abi || 'unknown'}</div>
          </div>
        </section>
      )}

      {/* Model List */}
      <div className="model-list">
        {models.length === 0 ? (
          <div className="empty-collection">
            <div className="empty-icon">
              <Database size={32} />
            </div>
            <h3>No models downloaded</h3>
            <p>Download models from the Model Zoo to start chatting.</p>
            <button className="btn-open-zoo" onClick={onOpenZoo}>
              Open Model Zoo
            </button>
          </div>
        ) : (
          <AnimatePresence mode="popLayout">
            {models.map((model, index) => {
              const isActive = activeModel?.id === model.id;
              const isExpanded = expandedId === model.id;

              return (
                <motion.div
                  key={model.id}
                  className={`model-item ${isActive ? 'active' : ''} ${isRunning && isActive ? 'running' : ''}`}
                  initial={{ opacity: 0, y: 20 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, x: -100 }}
                  transition={{ delay: index * 0.05 }}
                  layout
                >
                  {/* Main Row - tap to select & chat */}
                  <div 
                    className="model-item-main"
                    onClick={() => handleTap(model)}
                  >
                    <div className="model-item-left">
                      <div className="model-radio">
                        {isActive ? (
                          <motion.span 
                            className="radio-active"
                            initial={{ scale: 0 }}
                            animate={{ scale: 1 }}
                          />
                        ) : (
                          <span className="radio-inactive" />
                        )}
                      </div>
                      <div className="model-item-info">
                        <span className="model-item-name">{model.name}</span>
                        <span className="model-item-size mono">
                          {formatModelSize(getModelSizeBytes(model))}
                        </span>
                      </div>
                    </div>

                    <div className="model-item-right">
                      {isActive && isRunning && (
                        <span className="running-badge">Running</span>
                      )}
                      {isActive && (
                        <span className="active-badge">Active</span>
                      )}
                      <button
                        className="expand-toggle"
                        onClick={(e) => toggleExpand(e, model.id)}
                        aria-label={isExpanded ? 'Collapse details' : 'Expand details'}
                      >
                        <ChevronDown 
                          size={16} 
                          className={`expand-icon ${isExpanded ? 'expanded' : ''}`}
                        />
                      </button>
                    </div>
                  </div>

                  {/* Expanded Details */}
                  <AnimatePresence>
                    {isExpanded && (
                      <motion.div 
                        className="model-item-details"
                        initial={{ height: 0, opacity: 0 }}
                        animate={{ height: 'auto', opacity: 1 }}
                        exit={{ height: 0, opacity: 0 }}
                        transition={{ duration: 0.2 }}
                      >
                        <div className="details-grid">
                          <div className="detail">
                            <span className="detail-label">Disk usage</span>
                            <span className="detail-value mono">{formatBytes(model.downloadedBytes || getModelSizeBytes(model))}</span>
                          </div>
                          <div className="detail">
                            <span className="detail-label">Params</span>
                            <span className="detail-value mono">{model.params}</span>
                          </div>
                          <div className="detail">
                            <span className="detail-label">Task</span>
                            <span className="detail-value">{model.task}</span>
                          </div>
                          <div className="detail">
                            <span className="detail-label">Quantization</span>
                            <span className="detail-value mono">{model.quantizations?.join(', ') || 'Unknown'}</span>
                          </div>
                        </div>

                        {/* Primary action on its own row; secondary actions share one equal-width row */}
                        <div className="details-actions">
                          {isActive ? (
                            <button
                              className="btn-deselect"
                              onClick={(e) => {
                                e.stopPropagation();
                                onStop?.();
                              }}
                            >
                              Deselect
                            </button>
                          ) : (
                            <button
                              className="btn-select"
                              onClick={(e) => {
                                e.stopPropagation();
                                onSelect?.(model);
                              }}
                            >
                              <Check size={14} />
                              Select & Chat
                            </button>
                          )}

                          <div className="details-actions-secondary">
                            {/* Load/unload direct on-device model */}
                            {isNative && (
                              isActive ? (
                                <button
                                  className="btn-unmount"
                                  onClick={async (e) => {
                                    e.stopPropagation();
                                    if (onUnmountModel) await onUnmountModel(model);
                                  }}
                                >
                                  Unmount
                                </button>
                              ) : (
                                <button
                                  className="btn-mount"
                                  onClick={async (e) => {
                                    e.stopPropagation();
                                    if (onMountModel) await onMountModel(model);
                                  }}
                                >
                                  Mount
                                </button>
                              )
                            )}

                            <button
                              className="btn-delete"
                              onClick={(e) => {
                                e.stopPropagation();
                                onDelete?.(model);
                              }}
                            >
                              <Trash2 size={14} />
                              Delete
                            </button>

                          </div>
                        </div>

                        {/* Power-user metadata stays collapsed so actions are reachable without scrolling */}
                        <button
                          type="button"
                          className="tech-details-toggle"
                          onClick={(e) => { e.stopPropagation(); setTechDetailsOpen(value => !value); }}
                          aria-expanded={techDetailsOpen}
                        >
                          <span>Technical details</span>
                          <ChevronDown size={14} className={`expand-icon ${techDetailsOpen ? 'expanded' : ''}`} />
                        </button>
                        {techDetailsOpen && (
                          <div className="details-grid tech-details-grid">
                            <div className="detail">
                              <span className="detail-label">License</span>
                              <span className="detail-value">{model.license || 'Unknown'}</span>
                            </div>
                            <div className="detail">
                              <span className="detail-label">Source</span>
                              <span className="detail-value mono">{model.source || (model.sourceUri ? 'Device import' : 'Unknown')}</span>
                            </div>
                            <div className="detail">
                              <span className="detail-label">Revision</span>
                              <span className="detail-value mono">{model.revision?.slice(0, 12) || 'User supplied'}</span>
                            </div>
                            <div className="detail">
                              <span className="detail-label">Integrity</span>
                              <span className="detail-value">{model.integrity || (model.verified ? 'publisher-verified' : 'unverified')}</span>
                            </div>
                            <div className="detail">
                              <span className="detail-label">SHA-256</span>
                              <span className="detail-value mono" title={model.sha256 || ''}>{model.sha256 ? `${model.sha256.slice(0, 12)}…` : 'Unavailable'}</span>
                            </div>
                            <div className="detail">
                              <span className="detail-label">Downloaded</span>
                              <span className="detail-value">{formatDate(model.downloadedAt)}</span>
                            </div>
                          </div>
                        )}
                      </motion.div>
                    )}
                  </AnimatePresence>
                </motion.div>
              );
            })}
          </AnimatePresence>
        )}
      </div>

      {/* Footer */}
      <div className="collection-footer">
        <span className="storage-info mono">{storageSummary}</span>
        <button type="button" className="refresh-storage" onClick={() => onRefreshDevice?.()} aria-label="Refresh device storage"><RefreshCw size={14} /> Refresh</button>
      </div>

        </>
      )}

    </div>
  );
}
