/**
 * Files — the workspace Luna is allowed to touch.
 *
 * Folders in a grey group, files in a plain list, and the things you can undo
 * at the bottom. Locked paths render at rest, never in red.
 */

import { useMemo, useState } from 'react';
import { I, Sheet } from './ui.jsx';
import { bytes, clockOf, fileGlyph } from './format.js';
import { isSensitiveWorkspacePath } from '../../workspace/workspacePolicy.js';

function nodeAt(tree, path) {
  if (!path) return tree;
  for (const node of tree) {
    if (node.path === path) return node.children || [];
    if (node.type === 'folder' && path.startsWith(`${node.path}/`)) {
      const found = nodeAt(node.children || [], path);
      if (found) return found;
    }
  }
  return [];
}

export default function FilesScreen({
  tree = [], rootPath = '', loading = false, lastBackup,
  onChooseWorkspace, onRefresh, onRead, onSave, onCreateFile, onCreateFolder,
  onRename, onDelete, onUndo, onAsk, isNative = false,
}) {
  const [cwd, setCwd] = useState('');
  const [query, setQuery] = useState('');
  const [searching, setSearching] = useState(false);
  const [open, setOpen] = useState(null);      // { path, name, text, dirty }
  const [busy, setBusy] = useState(false);
  const [adding, setAdding] = useState(null);  // 'file' | 'folder'
  const [newName, setNewName] = useState('');
  const [draft, setDraft] = useState('');

  const items = useMemo(() => nodeAt(tree, cwd), [tree, cwd]);
  const filtered = useMemo(() => {
    if (!query.trim()) return items;
    const q = query.trim().toLowerCase();
    return items.filter(node => node.name.toLowerCase().includes(q));
  }, [items, query]);

  const folders = filtered.filter(node => node.type === 'folder');
  const files = filtered.filter(node => node.type !== 'folder');
  const crumb = cwd ? cwd.split('/').join(' / ') : (rootPath ? 'Workspace root' : 'No folder yet');

  const openFile = async node => {
    setBusy(true);
    try {
      const text = await onRead?.(node.path);
      setOpen({ path: node.path, name: node.name, text: String(text ?? ''), dirty: false });
      setDraft(String(text ?? ''));
    } catch (error) {
      setOpen({ path: node.path, name: node.name, text: '', error: error.message });
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    setBusy(true);
    try {
      await onSave?.(open.path, draft);
      setOpen(prev => ({ ...prev, text: draft, dirty: false }));
    } finally {
      setBusy(false);
    }
  };

  const create = async () => {
    const name = newName.trim();
    if (!name) return;
    const path = cwd ? `${cwd}/${name}` : name;
    setBusy(true);
    try {
      if (adding === 'folder') await onCreateFolder?.(path);
      else await onCreateFile?.(path);
      setAdding(null);
      setNewName('');
      await onRefresh?.();
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="screen">
      <div className="top">
        {cwd ? (
          <button type="button" className="ib" onClick={() => setCwd(cwd.split('/').slice(0, -1).join('/'))} aria-label="Back">
            <I n="chevron-left" />
          </button>
        ) : null}
        <span className="title">Files</span>
        <button type="button" className={`ib${searching ? ' on' : ''}`} onClick={() => { setSearching(!searching); setQuery(''); }} aria-label="Search this folder">
          <I n="magnifying-glass" />
        </button>
        <button type="button" className="ib" onClick={onChooseWorkspace} aria-label="Grant a folder">
          <I n="folder-plus" />
        </button>
      </div>

      {searching ? (
        <div className="field">
          <input value={query} onChange={e => setQuery(e.target.value)} placeholder="Filter this folder…" autoFocus />
        </div>
      ) : (
        <div className="crumb">
          <I n="folder-open" style={{ fontSize: 11, color: 'var(--ink-3)' }} />
          <span className="path mono">{crumb}</span>
          <span className="n">{items.length}</span>
        </div>
      )}

      <div className="grow">
        {loading && (
          <div className="running"><I n="circle-notch" spin style={{ fontSize: 11 }} />Reading the folder</div>
        )}

        {!rootPath && !loading && (
          <div className="empty">
            <I n="folder-plus" />
            <b>No folder yet</b>
            {isNative
              ? 'Grant Luna one folder. She can read and write inside it and nowhere else.'
              : 'Running in the browser preview, so this is a sandbox folder. On the phone you grant a real one.'}
            <div style={{ marginTop: 12 }}>
              <button type="button" className="btn sm" onClick={onChooseWorkspace}>
                <I n="folder-plus" />Choose a folder
              </button>
            </div>
          </div>
        )}

        {folders.length > 0 && (
          <>
            <div className="lbl">Folders</div>
            <div className="group">
              {folders.map(node => (
                <button type="button" className="row" key={node.path} onClick={() => setCwd(node.path)}>
                  <span className="tile"><I n="folder" /></span>
                  <span className="tx">
                    <b>{node.name}</b>
                    <span>{(node.children?.length ?? 0)} items</span>
                  </span>
                  <span className="end"><I n="chevron-right" /></span>
                </button>
              ))}
            </div>
          </>
        )}

        {files.length > 0 && (
          <>
            <div className="lbl">
              Files
              <button type="button" className="act" onClick={() => { setAdding('file'); setNewName(''); }}>Add</button>
            </div>
            <div className="list">
              {files.map(node => {
                const locked = isSensitiveWorkspacePath(node.path);
                const [glyph, brand] = locked ? ['lock', false] : fileGlyph(node.name);
                return (
                  <button
                    type="button"
                    className={`row${locked ? ' off' : ''}`}
                    key={node.path}
                    onClick={() => (locked ? null : openFile(node))}
                  >
                    <span className="tile"><I n={glyph} b={brand} /></span>
                    <span className="tx">
                      <b>{node.name}</b>
                      <span>{locked ? "Luna can't open this" : (node.modifiedAt ? `edited ${clockOf(node.modifiedAt)}` : 'in your folder')}</span>
                    </span>
                    <span className="end">{locked ? '—' : bytes(node.size)}</span>
                  </button>
                );
              })}
            </div>
          </>
        )}

        {rootPath && folders.length === 0 && files.length === 0 && !loading && (
          <div className="empty">
            <I n="folder-open" />
            <b>Nothing here</b>
            This folder is empty.
          </div>
        )}

        {rootPath && (
          <>
            <div className="lbl">This folder</div>
            <div className="group plain">
              <button type="button" className="row" onClick={() => { setAdding('folder'); setNewName(''); }}>
                <span className="tile"><I n="folder-plus" /></span>
                <span className="tx"><b>New folder</b><span>Inside {cwd || 'the root'}</span></span>
                <span className="end"><I n="chevron-right" /></span>
              </button>
              <button type="button" className="row" onClick={onUndo} disabled={!lastBackup}>
                <span className="tile"><I n="clock-rotate-left" /></span>
                <span className="tx">
                  <b>Undo Luna's edits</b>
                  <span>{lastBackup ? `backup kept from ${clockOf(lastBackup.createdAt)}` : 'nothing to undo yet'}</span>
                </span>
                <span className="end"><I n="chevron-right" /></span>
              </button>
              <button type="button" className="row" onClick={onRefresh}>
                <span className="tile"><I n="arrows-rotate" /></span>
                <span className="tx"><b>Rescan</b><span>Pick up changes made outside Luna</span></span>
                <span className="end"><I n="chevron-right" /></span>
              </button>
            </div>
          </>
        )}
      </div>

      <div className="comp">
        <span className="att"><I n="paperclip" /></span>
        <textarea
          rows={1}
          placeholder="Ask about this folder…"
          onKeyDown={e => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              const text = e.target.value.trim();
              if (!text) return;
              e.target.value = '';
              onAsk?.(cwd ? `In ${cwd}: ${text}` : text);
            }
          }}
        />
        <span className="go"><I n="arrow-up" /></span>
      </div>

      <Sheet
        open={Boolean(open)}
        title={open?.name || ''}
        onClose={() => setOpen(null)}
        action={draft !== open?.text ? (
          <button type="button" className="btn sm" onClick={save} disabled={busy}>
            <I n={busy ? 'circle-notch' : 'floppy-disk'} spin={busy} />Save
          </button>
        ) : null}
      >
        {open?.error ? (
          <div className="empty"><I n="triangle-exclamation" /><b>Could not open it</b>{open.error}</div>
        ) : (
          <>
            <textarea className="code" value={draft} onChange={e => setDraft(e.target.value)} spellCheck={false} />
            <div className="lbl">Actions</div>
            <div className="list">
              <button type="button" className="row" onClick={async () => {
                const name = window.prompt?.('New name', open.name);
                if (!name) return;
                await onRename?.(open.path, name);
                setOpen(null);
                await onRefresh?.();
              }}>
                <span className="tile"><I n="pen" /></span>
                <span className="tx"><b>Rename</b><span className="mono">{open?.path}</span></span>
              </button>
              <button type="button" className="row" onClick={async () => {
                if (!window.confirm?.(`Delete ${open.name}? A backup is kept.`)) return;
                await onDelete?.(open.path);
                setOpen(null);
                await onRefresh?.();
              }}>
                <span className="tile"><I n="trash-can" /></span>
                <span className="tx"><b>Delete</b><span>Recoverable from the backup</span></span>
              </button>
            </div>
          </>
        )}
      </Sheet>

      <Sheet open={Boolean(adding)} title={adding === 'folder' ? 'New folder' : 'New file'} onClose={() => setAdding(null)}>
        <div className="field">
          <label htmlFor="luna-new-name">Name</label>
          <input
            id="luna-new-name"
            value={newName}
            autoFocus
            placeholder={adding === 'folder' ? 'notes' : 'notes.md'}
            onChange={e => setNewName(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') create(); }}
          />
          <div className="help">Created in {cwd || 'the folder root'}.</div>
        </div>
        <button type="button" className="btn wide" onClick={create} disabled={!newName.trim() || busy}>
          <I n="check" />Create
        </button>
      </Sheet>
    </div>
  );
}
