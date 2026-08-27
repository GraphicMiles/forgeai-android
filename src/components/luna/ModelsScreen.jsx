/**
 * Models — what Luna thinks with.
 *
 * Three places a model can live: on the device, on your computer (Ollama), or
 * behind a cloud key. One filled-black surface: the model that is running.
 */

import { useState } from 'react';
import { I, Mark, Sheet } from './ui.jsx';
import { bytes } from './format.js';
import { MODEL_CATALOG } from '../../models/catalog.js';
import { CLOUD_PROVIDER_PRESETS } from '../../providers/cloudProviderStore.js';

const TABS = [
  { id: 'device', label: 'On device' },
  { id: 'computer', label: 'My computer' },
  { id: 'cloud', label: 'Cloud' },
];

export default function ModelsScreen({
  models = [], activeModel, downloads = {}, deviceCapability = {}, runtimeInfo, benchmark,
  ollamaConnected = false, endpoint = '', cloudProviders = [], isNative = false,
  onUse, onDelete, onStop, onDownload, onPauseDownload, onCancelDownload,
  onImportModel, onRefreshDevice, onAddCloudProvider, onRemoveCloudProvider, onUseCloudProvider,
}) {
  const [tab, setTab] = useState('device');
  const [addingCloud, setAddingCloud] = useState(false);
  const [preset, setPreset] = useState(CLOUD_PROVIDER_PRESETS[0]?.id || 'groq');
  const [apiKey, setApiKey] = useState('');
  const [cloudModel, setCloudModel] = useState(CLOUD_PROVIDER_PRESETS[0]?.defaultModel || '');
  const [cloudError, setCloudError] = useState('');
  const [detail, setDetail] = useState(null);

  const ramBytes = deviceCapability.ramBytes || (deviceCapability.ram ? deviceCapability.ram * 1024 ** 3 : 0);
  const installed = models.filter(model => model.source !== 'cloud' && !model.cloud);
  const installedIds = new Set(installed.map(model => model.id));
  const downloading = Object.entries(downloads)
    .map(([id, state]) => ({ ...state, model: MODEL_CATALOG.find(m => m.id === id) || models.find(m => m.id === id) || { id, name: id } }))
    .filter(entry => entry.status === 'downloading' || entry.status === 'paused');
  const downloadingIds = new Set(downloading.map(entry => entry.model.id));

  const available = MODEL_CATALOG.filter(model => !installedIds.has(model.id) && !downloadingIds.has(model.id));

  const fits = model => !ramBytes || !model.minRamBytes || model.minRamBytes <= ramBytes;
  const activeIsCloud = activeModel?.source === 'cloud' || activeModel?.cloud;
  const activeSize = activeModel?.sizeBytes || activeModel?.size;

  const addCloud = () => {
    const chosen = CLOUD_PROVIDER_PRESETS.find(p => p.id === preset);
    try {
      onAddCloudProvider?.({ provider: preset, apiKey, modelId: cloudModel || chosen?.defaultModel, baseUrl: chosen?.baseUrl, label: chosen?.label });
      setAddingCloud(false);
      setApiKey('');
      setCloudError('');
    } catch (error) {
      setCloudError(error.message);
    }
  };

  return (
    <div className="screen">
      <div className="top">
        <span className="title">Models</span>
        <button type="button" className="ib" onClick={onRefreshDevice} aria-label="Re-measure the device">
          <I n="arrows-rotate" />
        </button>
      </div>

      <div className="seg">
        {TABS.map(item => (
          <button type="button" key={item.id} className={tab === item.id ? 'on' : ''} onClick={() => setTab(item.id)}>
            {item.label}
          </button>
        ))}
      </div>

      <div className="grow">
        {tab === 'device' && (
          <>
            {activeModel && !activeIsCloud ? (
              <>
                <div className="lbl">Running now</div>
                <div className="hero">
                  <div className="r1">
                    <Mark size={36} tone="chip" />
                    <div className="nm">
                      <b>{activeModel.name}</b>
                      <span>Works offline</span>
                    </div>
                    <button type="button" className="pill ghost" onClick={onStop}>Stop</button>
                  </div>
                  <div className="grid">
                    <div className="cell"><b>{bytes(activeSize)}</b><span>on disk</span></div>
                    <div className="cell"><b>{runtimeInfo?.contextTokens ? `${Math.round(runtimeInfo.contextTokens / 1024)}k` : (activeModel.profile?.contextTokens ? `${Math.round(activeModel.profile.contextTokens / 1024)}k` : '—')}</b><span>context</span></div>
                    <div className="cell"><b>{benchmark?.tokensPerSecond ? `${benchmark.tokensPerSecond.toFixed(0)}/s` : '—'}</b><span>tokens</span></div>
                  </div>
                </div>
              </>
            ) : (
              <>
                <div className="lbl">Running now</div>
                <div className="empty">
                  <I n="microchip" />
                  <b>Nothing loaded</b>
                  Pick a downloaded model below and Luna will keep it in memory.
                </div>
              </>
            )}

            {downloading.length > 0 && (
              <>
                <div className="lbl">Downloading</div>
                <div className="group">
                  {downloading.map(entry => {
                    const total = entry.total || entry.model.sizeBytes || 0;
                    const done = entry.completed || (total * (entry.progress || 0)) / 100;
                    const pct = Math.max(0, Math.min(100, Math.round(entry.progress || (total ? (done / total) * 100 : 0))));
                    return (
                      <div className="row" key={entry.model.id}>
                        <span className="tile"><I n="cloud-arrow-down" /></span>
                        <div className="tx">
                          <b>{entry.model.name}</b>
                          <span>{bytes(done)} of {bytes(total)}{entry.status === 'paused' ? ' · paused' : ''}</span>
                          <span className="bar"><b style={{ width: `${pct}%` }} /></span>
                        </div>
                        <div className="end">
                          <button type="button" className="ib" onClick={() => (entry.status === 'paused' ? onDownload?.(entry.model) : onPauseDownload?.(entry.model))} aria-label="Pause or resume">
                            <I n={entry.status === 'paused' ? 'play' : 'pause'} />
                          </button>
                          <button type="button" className="ib" onClick={() => onCancelDownload?.(entry.model)} aria-label="Cancel">
                            <I n="xmark" />
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </>
            )}

            {installed.length > 0 && (
              <>
                <div className="lbl">On this phone</div>
                <div className="list">
                  {installed.map(model => (
                    <button type="button" className="row" key={model.id} onClick={() => setDetail(model)}>
                      <span className="tile"><I n="microchip" /></span>
                      <span className="tx">
                        <b>{model.name}</b>
                        <span>{bytes(model.sizeBytes || model.size)} · downloaded</span>
                      </span>
                      <span className="end">
                        {activeModel?.id === model.id
                          ? <I n="check" />
                          : <span className="btn soft sm" onClick={e => { e.stopPropagation(); onUse?.(model); }}>Use</span>}
                      </span>
                    </button>
                  ))}
                </div>
              </>
            )}

            <div className="lbl">You can add</div>
            <div className="list">
              {available.map(model => {
                const ok = fits(model);
                return (
                  <div className={`row${ok ? '' : ' off'}`} key={model.id}>
                    <span className="tile"><I n={ok ? 'microchip' : 'memory'} /></span>
                    <div className="tx">
                      <b>{model.name}</b>
                      <span>{bytes(model.sizeBytes)} · {ok ? 'fits your phone' : `needs ${model.minRam} GB of RAM`}</span>
                    </div>
                    <div className="end">
                      {ok
                        ? <button type="button" className="btn soft sm" onClick={() => onDownload?.(model)}>Get</button>
                        : "Won't run"}
                    </div>
                  </div>
                );
              })}
            </div>

            <div className="lbl">Your phone</div>
            <div className="stats">
              <div className="stat">
                <I n="memory" />
                <div><b>{ramBytes ? bytes(ramBytes) : '—'}</b><span>RAM</span></div>
              </div>
              <div className="stat">
                <I n="hard-drive" />
                <div><b>{deviceCapability.availableStorageBytes ? bytes(deviceCapability.availableStorageBytes) : '—'}</b><span>free</span></div>
              </div>
            </div>

            {isNative && (
              <button type="button" className="btn soft wide" onClick={onImportModel}>
                <I n="file-import" />Import a GGUF file
              </button>
            )}
          </>
        )}

        {tab === 'computer' && (
          <>
            <div className="lbl">Ollama on your computer</div>
            <div className="group">
              <div className="row">
                <span className="tile"><I n={ollamaConnected ? 'plug-circle-check' : 'plug-circle-xmark'} /></span>
                <div className="tx">
                  <b>{ollamaConnected ? 'Connected' : 'Not reachable'}</b>
                  <span className="mono">{endpoint || 'no endpoint set'}</span>
                </div>
              </div>
            </div>
            <div className="note">
              <I n="circle-info" />
              <span>Luna talks to Ollama over your local network. Set the endpoint in <b>Settings → Connections</b>, and keep the phone and the computer on the same Wi-Fi.</span>
            </div>
            {ollamaConnected && (
              <>
                <div className="lbl">Models it is serving</div>
                <div className="list">
                  {models.filter(model => model.viaOllama || model.ollamaName).map(model => (
                    <div className="row" key={model.id}>
                      <span className="tile"><I n="server" /></span>
                      <div className="tx"><b>{model.name}</b><span>Runs on your computer</span></div>
                      <div className="end">
                        <button type="button" className="btn soft sm" onClick={() => onUse?.(model)}>Use</button>
                      </div>
                    </div>
                  ))}
                </div>
              </>
            )}
          </>
        )}

        {tab === 'cloud' && (
          <>
            <div className="lbl">
              Connected keys
              <button type="button" className="act" onClick={() => setAddingCloud(true)}>Add</button>
            </div>
            {cloudProviders.length === 0 ? (
              <div className="empty">
                <I n="cloud" />
                <b>No cloud keys</b>
                A key lets Luna fall back to a hosted model when the phone can't hold one. Nothing is uploaded until you use it.
              </div>
            ) : (
              <div className="list">
                {cloudProviders.map(provider => (
                  <div className="row" key={provider.id}>
                    <span className="tile"><I n="cloud" /></span>
                    <div className="tx">
                      <b>{provider.label}</b>
                      <span className="mono">{provider.modelId}</span>
                    </div>
                    <div className="end">
                      <button type="button" className="btn soft sm" onClick={() => onUseCloudProvider?.(provider)}>Use</button>
                      <button type="button" className="ib" onClick={() => onRemoveCloudProvider?.(provider.id)} aria-label="Remove">
                        <I n="trash-can" />
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
            <div className="note">
              <I n="key" />
              <span>Keys are stored on this device only. Luna never sends your files to a cloud model unless a step needs it.</span>
            </div>
          </>
        )}
      </div>

      <Sheet open={Boolean(detail)} title={detail?.name || ''} onClose={() => setDetail(null)}>
        <div className="list">
          <button type="button" className="row" onClick={() => { onUse?.(detail); setDetail(null); }}>
            <span className="tile"><I n="play" /></span>
            <span className="tx"><b>Use this model</b><span>Load it into memory</span></span>
          </button>
          <button type="button" className="row" onClick={() => { onDelete?.(detail); setDetail(null); }}>
            <span className="tile"><I n="trash-can" /></span>
            <span className="tx"><b>Delete</b><span>Frees {bytes(detail?.sizeBytes || detail?.size)}</span></span>
          </button>
        </div>
        <div className="lbl">Details</div>
        <div className="group plain">
          <div className="row"><div className="tx"><b>Quantization</b></div><div className="end">{detail?.quantization || '—'}</div></div>
          <div className="row"><div className="tx"><b>Licence</b></div><div className="end">{detail?.license || '—'}</div></div>
          <div className="row"><div className="tx"><b>Checksum</b></div><div className="end mono">{detail?.sha256 ? `${detail.sha256.slice(0, 10)}…` : '—'}</div></div>
        </div>
      </Sheet>

      <Sheet open={addingCloud} title="Add a cloud key" onClose={() => setAddingCloud(false)}>
        <div className="field">
          <label htmlFor="luna-cloud-provider">Provider</label>
          <select
            id="luna-cloud-provider"
            value={preset}
            onChange={e => {
              setPreset(e.target.value);
              const next = CLOUD_PROVIDER_PRESETS.find(p => p.id === e.target.value);
              setCloudModel(next?.defaultModel || '');
            }}
          >
            {CLOUD_PROVIDER_PRESETS.map(item => <option key={item.id} value={item.id}>{item.label}</option>)}
          </select>
          <div className="help">{CLOUD_PROVIDER_PRESETS.find(p => p.id === preset)?.freeTier || ''}</div>
        </div>
        <div className="field">
          <label htmlFor="luna-cloud-key">API key</label>
          <input id="luna-cloud-key" value={apiKey} onChange={e => setApiKey(e.target.value)} placeholder={CLOUD_PROVIDER_PRESETS.find(p => p.id === preset)?.keyPrefix || 'sk-'} />
        </div>
        <div className="field">
          <label htmlFor="luna-cloud-model">Model</label>
          <input id="luna-cloud-model" value={cloudModel} onChange={e => setCloudModel(e.target.value)} />
        </div>
        {cloudError && <div className="note"><I n="triangle-exclamation" /><span>{cloudError}</span></div>}
        <button type="button" className="btn wide" onClick={addCloud} disabled={!apiKey.trim()}>
          <I n="check" />Save key
        </button>
      </Sheet>
    </div>
  );
}
