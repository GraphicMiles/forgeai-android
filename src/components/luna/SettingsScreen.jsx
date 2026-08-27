/**
 * Settings — three questions: how Luna acts, what she can reach, what she keeps.
 * The held-tool chips are the one filled-black surface on this screen.
 */

import { useEffect, useState } from 'react';
import { I, Sheet } from './ui.jsx';
import { EXECUTION_MODES, readExecutionMode, writeExecutionMode } from '../../agent/executionMode.js';
import { MUTATING_TOOLS, READ_ONLY_TOOLS } from '../../agent/toolPolicy.js';
import { WORKSPACE_LIMITS } from '../../workspace/workspacePolicy.js';
import { isFailoverEnabled, setFailoverEnabled } from '../../providers/cloudProviderStore.js';
import { clearGithubToken, hasGithubToken, setFullAutonomy, storeGithubToken } from '../../nativeBridge.js';
import { clearErrorLog, readErrorLog, recordError } from '../../utils/errorLog.js';

const TABS = [
  { id: 'agent', label: 'Agent' },
  { id: 'connections', label: 'Connections' },
  { id: 'data', label: 'Data' },
];

const READ_LABELS = {
  read_file: 'Read a file', read_symbol: 'Read a function', list_files: 'List a folder',
  search_code: 'Search text', search_web: 'Search the web', fetch_page: 'Open a page',
  git_status: 'See git changes', git_diff: 'See a diff', git_log: 'Read history',
  ask_user: 'Ask you', respond: 'Answer',
};
const WRITE_LABELS = {
  write_file: 'Write', create_file: 'Create', create_folder: 'New folder', delete_file: 'Delete',
  run_terminal: 'Run a command', git_clone: 'Clone', git_commit: 'Commit', git_push: 'Push',
};

export default function SettingsScreen({
  endpoint = '', onEndpointChange, onReset, isNative = false, runtimeInfo, deviceCapability = {},
}) {
  const [tab, setTab] = useState('agent');
  const [mode, setMode] = useState(() => readExecutionMode());
  const [failover, setFailover] = useState(() => isFailoverEnabled());
  const [tokenSaved, setTokenSaved] = useState(false);
  const [tokenDraft, setTokenDraft] = useState('');
  const [tokenSheet, setTokenSheet] = useState(false);
  const [logSheet, setLogSheet] = useState(false);
  const [log, setLog] = useState([]);
  const [protectedSheet, setProtectedSheet] = useState(false);

  useEffect(() => {
    let alive = true;
    hasGithubToken()
      .then(result => { if (alive) setTokenSaved(Boolean(result?.stored ?? result)); })
      .catch(() => {});
    return () => { alive = false; };
  }, []);

  const setModeAndPersist = next => {
    setMode(next);
    writeExecutionMode(next);
    if (isNative) setFullAutonomy(next === EXECUTION_MODES.AUTO).catch(error => recordError(error, 'autonomy-toggle'));
  };

  const readChips = READ_ONLY_TOOLS.map(tool => READ_LABELS[tool] || tool);
  const shownRead = readChips.slice(0, 5);
  const restRead = readChips.length - shownRead.length;

  return (
    <div className="screen">
      <div className="top"><span className="title">Settings</span></div>

      <div className="seg">
        {TABS.map(item => (
          <button type="button" key={item.id} className={tab === item.id ? 'on' : ''} onClick={() => setTab(item.id)}>
            {item.label}
          </button>
        ))}
      </div>

      <div className="grow">
        {tab === 'agent' && (
          <>
            <div className="lbl">How Luna acts</div>
            <div className="group">
              <button type="button" className="row" onClick={() => setModeAndPersist(EXECUTION_MODES.ASK)}>
                <span className="tile"><I n="hand" /></span>
                <span className="tx"><b>Ask before acting</b><span>Stops before it changes anything</span></span>
                <span className={`sw${mode === EXECUTION_MODES.ASK ? '' : ' off'}`}><b /></span>
              </button>
              <button type="button" className="row" onClick={() => setModeAndPersist(EXECUTION_MODES.AUTO)}>
                <span className="tile"><I n="bolt" /></span>
                <span className="tx"><b>Run unattended</b><span>Never stops to ask</span></span>
                <span className={`sw${mode === EXECUTION_MODES.AUTO ? '' : ' off'}`}><b /></span>
              </button>
            </div>
            <div className="note">
              <I n="triangle-exclamation" />
              <span>A web page Luna reads could contain instructions she follows. <b>Only run unattended in folders you trust.</b></span>
            </div>

            <div className="lbl">Never needs permission</div>
            <div className="chips">
              {shownRead.map(label => <span className="chip" key={label}>{label}</span>)}
              {restRead > 0 && <span className="chip">+{restRead}</span>}
            </div>

            <div className="lbl tight">Always asks first</div>
            <div className="chips">
              {MUTATING_TOOLS.map(tool => <span className="chip k" key={tool}>{WRITE_LABELS[tool] || tool}</span>)}
            </div>

            <div className="lbl">Safety</div>
            <div className="list">
              <div className="row">
                <span className="tile"><I n="weight-hanging" /></span>
                <div className="tx"><b>File size cap</b><span>Reading and writing</span></div>
                <div className="end">{Math.round(WORKSPACE_LIMITS.writeBytes / (1024 * 1024))} MB</div>
              </div>
              <button type="button" className="row" onClick={() => setProtectedSheet(true)}>
                <span className="tile"><I n="shield-halved" /></span>
                <span className="tx"><b>Protected files</b><span>Keys and credentials stay off limits</span></span>
                <span className="end"><I n="chevron-right" /></span>
              </button>
            </div>
          </>
        )}

        {tab === 'connections' && (
          <>
            <div className="lbl">Your computer</div>
            <div className="field">
              <label htmlFor="luna-endpoint">Ollama endpoint</label>
              <input
                id="luna-endpoint"
                className="mono"
                value={endpoint}
                onChange={e => onEndpointChange?.(e.target.value)}
                placeholder="http://192.168.1.20:11434"
              />
              <div className="help">Used when you pick a model under Models → My computer.</div>
            </div>

            <div className="lbl">Cloud</div>
            <div className="group">
              <button
                type="button"
                className="row"
                onClick={() => { const next = !failover; setFailover(next); setFailoverEnabled(next); }}
              >
                <span className="tile"><I n="cloud-arrow-up" /></span>
                <span className="tx"><b>Fall back to cloud</b><span>Only when the local model can't answer</span></span>
                <span className={`sw${failover ? '' : ' off'}`}><b /></span>
              </button>
            </div>

            <div className="lbl">Credentials</div>
            <div className="list">
              <button type="button" className="row" onClick={() => setTokenSheet(true)}>
                <span className="tile"><I n="github" b /></span>
                <span className="tx">
                  <b>GitHub token</b>
                  <span>{tokenSaved ? 'Saved in the Android keystore' : 'Not set'}</span>
                </span>
                <span className="end"><I n="chevron-right" /></span>
              </button>
            </div>
          </>
        )}

        {tab === 'data' && (
          <>
            <div className="lbl">This build</div>
            <div className="group plain">
              <div className="row">
                <span className="tile"><I n="mobile-screen" /></span>
                <div className="tx"><b>{isNative ? 'Android' : 'Browser preview'}</b><span>{deviceCapability.platform || 'unknown platform'}</span></div>
              </div>
              <div className="row">
                <span className="tile"><I n="microchip" /></span>
                <div className="tx">
                  <b>Runtime</b>
                  <span className="mono">{runtimeInfo?.backend || 'llama.cpp'}{runtimeInfo?.threads ? ` · ${runtimeInfo.threads} threads` : ''}</span>
                </div>
              </div>
            </div>

            <div className="lbl">Diagnostics</div>
            <div className="list">
              <button type="button" className="row" onClick={() => { setLog(readErrorLog()); setLogSheet(true); }}>
                <span className="tile"><I n="bug" /></span>
                <span className="tx"><b>Error log</b><span>Kept on the device only</span></span>
                <span className="end"><I n="chevron-right" /></span>
              </button>
              <button type="button" className="row" onClick={onReset}>
                <span className="tile"><I n="rotate-left" /></span>
                <span className="tx"><b>Reset app data</b><span>Chats and settings. Models stay.</span></span>
                <span className="end"><I n="chevron-right" /></span>
              </button>
            </div>
          </>
        )}
      </div>

      <Sheet open={protectedSheet} title="Protected files" onClose={() => setProtectedSheet(false)}>
        <div className="note">
          <I n="shield-halved" />
          <span>Luna refuses to read or write these, in any folder you grant her — even when running unattended.</span>
        </div>
        <div className="chips" style={{ paddingTop: 14 }}>
          {['.env', '.git/config', 'id_rsa', '.ssh', '.netrc', 'credentials', 'keystore', '*.pem', '*.key'].map(item => (
            <span className="chip mono" key={item}>{item}</span>
          ))}
        </div>
      </Sheet>

      <Sheet open={tokenSheet} title="GitHub token" onClose={() => setTokenSheet(false)}>
        <div className="field">
          <label htmlFor="luna-token">Personal access token</label>
          <input id="luna-token" value={tokenDraft} onChange={e => setTokenDraft(e.target.value)} placeholder="ghp_…" />
          <div className="help">Stored by Android in the hardware keystore. It never enters a prompt.</div>
        </div>
        <button
          type="button"
          className="btn wide"
          disabled={!tokenDraft.trim()}
          onClick={async () => {
            try {
              await storeGithubToken(tokenDraft.trim());
              setTokenSaved(true);
              setTokenDraft('');
              setTokenSheet(false);
            } catch (error) { recordError(error, 'store-token'); }
          }}
        >
          <I n="check" />Save token
        </button>
        {tokenSaved && (
          <button
            type="button"
            className="btn soft wide"
            onClick={async () => {
              try { await clearGithubToken(); setTokenSaved(false); }
              catch (error) { recordError(error, 'clear-token'); }
            }}
          >
            <I n="trash-can" />Remove the saved token
          </button>
        )}
      </Sheet>

      <Sheet
        open={logSheet}
        title="Error log"
        onClose={() => setLogSheet(false)}
        action={log.length ? (
          <button type="button" className="btn sm soft" onClick={() => { clearErrorLog(); setLog([]); }}>Clear</button>
        ) : null}
      >
        {log.length === 0 ? (
          <div className="empty"><I n="circle-check" /><b>Nothing logged</b>No errors have been recorded on this device.</div>
        ) : (
          <div className="list">
            {log.map((entry, i) => (
              <div className="row" key={i}>
                <span className="tile"><I n="bug" /></span>
                <div className="tx"><b>{entry.context || 'app'}</b><span>{entry.message}</span></div>
              </div>
            ))}
          </div>
        )}
      </Sheet>
    </div>
  );
}
