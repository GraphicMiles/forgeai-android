import { useEffect, useState } from 'react';
import { Bot, Bug, Cpu, Database, Plug, RefreshCw, ShieldCheck, Trash2, Wifi } from 'lucide-react';
import { readErrorLog, clearErrorLog, recordError } from '../utils/errorLog.js';
import { clearGithubToken, hasGithubToken, setFullAutonomy, storeGithubToken } from '../nativeBridge.js';
import { EXECUTION_MODES, readExecutionMode, writeExecutionMode } from '../agent/executionMode.js';
import { isFailoverEnabled, setFailoverEnabled } from '../providers/cloudProviderStore.js';
import DropdownMenu from './DropdownMenu.jsx';
import './Settings.css';

const SETTINGS_SECTIONS = Object.freeze([
  { id: 'agent', label: 'Agent', icon: Bot, description: 'How much Luna does without asking.' },
  { id: 'connections', label: 'Connections', icon: Plug, description: 'Runtime endpoint, cloud failover, and credentials.' },
  { id: 'data', label: 'Data', icon: Database, description: 'Local data and diagnostics.' },
]);

const EXECUTION_OPTIONS = [
  { value: EXECUTION_MODES.ASK, label: 'Ask first' },
  { value: EXECUTION_MODES.AUTO, label: 'Run unattended' },
];

function SettingsHeader({ activeSection, onSectionChange }) {
  return (
    <div className="settings-nav-shell">
      <div className="settings-nav-title">
        <span className="settings-eyebrow">Settings</span>
      </div>
      <div className="settings-tabs" role="tablist" aria-label="Settings sections">
        {SETTINGS_SECTIONS.map(section => {
          const Icon = section.icon;
          return (
            <button
              key={section.id}
              type="button"
              role="tab"
              aria-selected={activeSection === section.id}
              className={activeSection === section.id ? 'active' : ''}
              onClick={() => onSectionChange(section.id)}
              title={section.description}
            >
              <Icon size={18} />
              <span>{section.label}</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}

function SettingCard({ title, icon: Icon, children, description }) {
  return (
    <section className="settings-card">
      <h3>{Icon && <Icon size={16} />} {title}</h3>
      {description && <p className="setting-help first">{description}</p>}
      {children}
    </section>
  );
}

function SettingField({ label, htmlFor, children, help }) {
  return (
    <div className="setting-field">
      <label className="setting-label" htmlFor={htmlFor}>{label}</label>
      {children}
      {help && <p className="setting-help">{help}</p>}
    </div>
  );
}

export default function Settings({
  endpoint,
  onEndpointChange,
  onReset,
  isNative = false,
}) {
  const [activeSection, setActiveSection] = useState('agent');
  const [notice, setNotice] = useState(null);
  const [value, setValue] = useState(endpoint);
  const [errors, setErrors] = useState(() => readErrorLog());
  const [executionMode, setExecutionMode] = useState(readExecutionMode);
  const [failover, setFailover] = useState(() => isFailoverEnabled());
  const [githubPat, setGithubPat] = useState('');
  const [githubStored, setGithubStored] = useState(false);

  const showNotice = (message, type = 'success') => setNotice({ message, type, at: Date.now() });

  useEffect(() => setValue(endpoint), [endpoint]);

  useEffect(() => {
    if (!isNative) return;
    hasGithubToken().then(result => setGithubStored(Boolean(result.stored)))
      .catch(error => recordError(error, 'settings-github-token-status'));
  }, [isNative]);

  const saveEndpoint = () => {
    const next = value.trim().replace(/\/$/, '');
    if (!next) return showNotice('Endpoint cannot be empty.', 'error');
    try { localStorage.setItem('luna_endpoint', next); }
    catch (error) { recordError(error, 'settings-save-endpoint'); }
    onEndpointChange?.(next);
    showNotice('Runtime endpoint saved.');
  };

  const changeExecutionMode = async next => {
    if (next === EXECUTION_MODES.AUTO) {
      const accepted = window.confirm(
        'Unattended mode lets Luna write and delete files, run sandbox shell commands, and commit or push Git changes without asking each time.\n\nWeb pages and repository content can contain prompt injection. Enable it?',
      );
      if (!accepted) return;
    }
    setExecutionMode(writeExecutionMode(next));
    if (isNative) {
      try { await setFullAutonomy(next === EXECUTION_MODES.AUTO); }
      catch (error) { recordError(error, 'settings-execution-mode'); }
    }
    showNotice('Execution mode updated.');
  };

  const saveGithubToken = async () => {
    if (!githubPat.trim()) return showNotice('Enter a GitHub personal access token first.', 'error');
    try {
      await storeGithubToken(githubPat.trim());
      setGithubPat('');
      setGithubStored(true);
      showNotice('GitHub token stored in the Android Keystore.');
    } catch (error) {
      recordError(error, 'settings-store-github-token');
      showNotice(`Could not store the token: ${error.message}`, 'error');
    }
  };

  const forgetGithubToken = async () => {
    try {
      await clearGithubToken();
      setGithubStored(false);
      showNotice('GitHub token removed.');
    } catch (error) {
      recordError(error, 'settings-clear-github-token');
      showNotice(`Could not remove the token: ${error.message}`, 'error');
    }
  };

  const renderAgent = () => (
    <div className="settings-section-grid">
      <SettingCard
        icon={ShieldCheck}
        title="Execution mode"
        description="Reading files, searching code, and web lookups never interrupt you. This controls everything that changes something."
      >
        <SettingField
          label="When Luna wants to write, delete, run a command, or push"
          htmlFor="execution-mode"
          help={executionMode === EXECUTION_MODES.AUTO
            ? 'Unattended: actions run immediately. You can still stop a run at any point.'
            : 'Ask first: each action shows the exact path, content, or command before it runs.'}
        >
          <DropdownMenu
            id="execution-mode"
            value={executionMode}
            options={EXECUTION_OPTIONS}
            onChange={changeExecutionMode}
          />
        </SettingField>
      </SettingCard>
    </div>
  );

  const renderConnections = () => (
    <div className="settings-section-grid">
      <SettingCard
        icon={Wifi}
        title="Ollama development preview"
        description="Browser mode talks to a local Ollama endpoint. On Android, inference runs on the bundled llama.cpp runtime instead."
      >
        <SettingField label="Endpoint" htmlFor="ollama-endpoint">
          <div className="setting-row">
            <input id="ollama-endpoint" value={value} onChange={event => setValue(event.target.value)} />
            <button onClick={saveEndpoint}><RefreshCw size={14} /> Save</button>
          </div>
        </SettingField>
      </SettingCard>

      <SettingCard
        icon={Plug}
        title="Cloud provider failover"
        description="If the active cloud model runs out of quota or gets rate-limited mid-task, continue on the next configured provider instead of stopping."
      >
        <label className="toggle-row">
          <input
            type="checkbox"
            checked={failover}
            onChange={event => { setFailover(event.target.checked); setFailoverEnabled(event.target.checked); }}
          />
          <span>Auto-failover between cloud providers {failover ? '(on)' : '(off)'}</span>
        </label>
        <p className="setting-help">Add providers in My Collection → Cloud. Bad API keys and missing models are never failed over — only quota, rate-limit, server, and network errors.</p>
      </SettingCard>

      {isNative && (
        <SettingCard
          icon={Plug}
          title="GitHub token"
          description="Stored in the Android Keystore. It is never added to a model prompt or a terminal environment."
        >
          <SettingField label="Personal access token" htmlFor="github-pat" help={`Token stored: ${githubStored ? 'yes' : 'no'}.`}>
            <div className="setting-row">
              <input id="github-pat" type="password" value={githubPat} onChange={event => setGithubPat(event.target.value)} placeholder="ghp_…" />
              <button onClick={saveGithubToken}>Save</button>
              {githubStored && <button className="danger" onClick={forgetGithubToken}>Forget</button>}
            </div>
          </SettingField>
        </SettingCard>
      )}
    </div>
  );

  const renderData = () => (
    <div className="settings-section-grid">
      <SettingCard icon={Cpu} title="Runtime" description={isNative
        ? 'Android runs the bundled llama.cpp CPU runtime. Pick or mount a model from your collection.'
        : 'Web mode uses Ollama as a development preview.'}>
        <p className="setting-help">Platform: {(typeof window !== 'undefined' && window.Capacitor?.getPlatform?.()) || 'web'}</p>
      </SettingCard>

      <SettingCard icon={Database} title="Local data" description="Chats and model metadata stay in app storage. Downloaded models and workspace backups are never touched by a reset.">
        <div className="setting-row wrap">
          <button className="danger" onClick={onReset}><Trash2 size={14} /> Reset app data</button>
        </div>
      </SettingCard>

      <SettingCard icon={Bug} title="Error log" description="Runtime errors are recorded locally for debugging.">
        <div className="setting-row wrap">
          <button onClick={() => setErrors(readErrorLog())}>Refresh</button>
          <button onClick={() => { clearErrorLog(); setErrors([]); showNotice('Error log cleared.'); }}>Clear</button>
        </div>
        <pre className="settings-error-log">
          {errors.length ? errors.map(error => `${error.time} [${error.context}] ${error.message}`).join('\n') : 'No recorded errors.'}
        </pre>
      </SettingCard>
    </div>
  );

  const activeMeta = SETTINGS_SECTIONS.find(section => section.id === activeSection) || SETTINGS_SECTIONS[0];

  return (
    <div className="settings-screen">
      <div className="screen-pad settings-layout-pad">
        <SettingsHeader activeSection={activeSection} onSectionChange={setActiveSection} />
        {notice && <div className={`settings-notice ${notice.type}`}>{notice.message}</div>}
        <div className="settings-section-heading compact">
          <h3>{activeMeta.label}</h3>
          <p>{activeMeta.description}</p>
        </div>
        {activeSection === 'connections' ? renderConnections() : activeSection === 'data' ? renderData() : renderAgent()}
      </div>
    </div>
  );
}
