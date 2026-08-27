/**
 * Chat — the working screen.
 *
 * One 27px title, one filled-black surface (the approval card). Everything else
 * is grey on white: the thread, the step group, the running pill.
 */

import { Fragment, useEffect, useRef, useState } from 'react';
import { I, Mark, Sheet } from './ui.jsx';
import { ago } from './format.js';
import { describeToolCall } from '../../agent/toolPolicy.js';
import { isUnattended } from '../../agent/executionMode.js';

const STEP_LABELS = {
  read_file: 'Read a file', list_files: 'Listed the folder', search_code: 'Searched the code',
  search_web: 'Searched the web', fetch_page: 'Read a page', write_file: 'Wrote a file',
  create_file: 'Created a file', create_folder: 'Created a folder', delete_file: 'Deleted a file',
  run_terminal: 'Ran a command', git_status: 'Checked git', git_diff: 'Read the diff',
  git_log: 'Read the history', git_commit: 'Committed', git_push: 'Pushed', respond: 'Answered',
};

function stepTitle(step) {
  if (step.type === 'thought') return step.title || 'Thinking';
  if (step.type === 'result_success') return 'Done';
  if (step.type === 'result_error') return 'Failed';
  const tool = String(step.title || '').split(' ')[0];
  return STEP_LABELS[tool] || step.title || 'Working';
}

/** Inline `code` and fenced blocks, nothing else — the thread stays quiet. */
function Rich({ text = '' }) {
  const parts = String(text).split(/```([\s\S]*?)```/g);
  return parts.map((part, i) => {
    if (i % 2 === 1) return <pre key={i}>{part.replace(/^\w*\n/, '')}</pre>;
    const inline = part.split(/`([^`]+)`/g);
    return (
      <Fragment key={i}>
        {inline.map((chunk, j) => (j % 2 ? <em key={j}>{chunk}</em> : chunk))}
      </Fragment>
    );
  });
}

export default function ChatScreen({
  messages = [], isTyping = false, reasoningSteps = [], isAgentThinking = false,
  pendingActions = [], onSend, onStop, onApprove, onDiscard,
  activeModel, models = [], onSelectModel, onOpenModels,
  workspaceName = '', conversations = [], activeConversationId,
  onSwitchConversation, onNewConversation, onRenameConversation,
  onDeleteConversation, onExportChat, onClearChat, elapsed = '',
}) {
  const [draft, setDraft] = useState('');
  const [history, setHistory] = useState(false);
  const [menu, setMenu] = useState(false);
  const [picker, setPicker] = useState(false);
  const scroller = useRef(null);
  const stick = useRef(true);

  const title = conversations.find(c => c.id === activeConversationId)?.title || 'New chat';
  const visible = messages.filter(m => m.role !== 'system' || m.content);
  const lastAssistant = [...visible].reverse().find(m => m.role === 'assistant');
  const showSteps = isAgentThinking || reasoningSteps.length > 0;

  useEffect(() => {
    if (!stick.current) return undefined;
    const id = requestAnimationFrame(() => {
      const el = scroller.current;
      if (el) el.scrollTop = el.scrollHeight;
    });
    return () => cancelAnimationFrame(id);
  }, [messages, reasoningSteps, isTyping, pendingActions.length]);

  const send = () => {
    const text = draft.trim();
    if (!text || isTyping) return;
    stick.current = true;
    setDraft('');
    onSend?.(text);
  };

  return (
    <div className="screen">
      <div className="top">
        <Mark size={32} />
        <span className="title sm">{title}</span>
        <button type="button" className="ib" onClick={() => setHistory(true)} aria-label="Chat history">
          <I n="clock-rotate-left" />
        </button>
        <button type="button" className="ib" onClick={() => setMenu(true)} aria-label="More">
          <I n="ellipsis" />
        </button>
      </div>

      <div className="pad" style={{ paddingBottom: 10, display: 'flex', gap: 7 }}>
        <button type="button" className="ctx" onClick={() => setPicker(true)}>
          <I n="microchip" />
          <span>{activeModel?.name || 'Pick a model'}</span>
          <span className="sep" />
          <I n="folder-open" />
          <span>{workspaceName || 'No folder'}</span>
        </button>
        {isUnattended() && (
          <span className="ctx" title="Luna will not stop to ask">
            <I n="bolt" />
            <span>Unattended</span>
          </span>
        )}
      </div>

      <div
        className="grow"
        ref={scroller}
        onScroll={e => {
          const { scrollTop, scrollHeight, clientHeight } = e.target;
          stick.current = scrollHeight - scrollTop - clientHeight < 90;
        }}
      >
        {visible.length === 0 && !isTyping ? (
          <div className="empty">
            <Mark size={40} style={{ margin: '0 auto 10px' }} />
            <b>{activeModel ? 'Give Luna a job' : 'Pick a model first'}</b>
            {activeModel
              ? 'Point her at a folder and describe the outcome. She reads before she writes, and stops before anything permanent.'
              : 'Models live on the Models tab. Download one, or connect your computer or a cloud key.'}
            {!activeModel && (
              <div style={{ marginTop: 12 }}>
                <button type="button" className="btn sm" onClick={onOpenModels}>
                  <I n="cube" />Open Models
                </button>
              </div>
            )}
          </div>
        ) : (
          <div className="thread">
            {visible.map(message => {
              if (message.role === 'user') return <div key={message.id} className="you">{message.content}</div>;
              if (message.role === 'system') {
                return (
                  <div key={message.id} className="sys">
                    <I n={message.level === 'error' ? 'circle-exclamation' : message.level === 'warn' ? 'triangle-exclamation' : 'circle-info'} />
                    <span>{message.content}</span>
                  </div>
                );
              }
              const streaming = isTyping && message.id === lastAssistant?.id;
              if (!message.content && !streaming) return null;
              return (
                <div key={message.id} className="luna">
                  <Mark size={22} style={{ marginTop: 2 }} />
                  <div className="tx">
                    <Rich text={message.content} />
                    {streaming && !message.content && <span className="cursor" />}
                  </div>
                </div>
              );
            })}
          </div>
        )}

        {showSteps && (
          <div className="steps">
            {reasoningSteps.map((step, i) => (
              <div key={i} className={`step${step.type === 'result_error' ? ' pending' : ''}`}>
                <span className="dot">
                  <I n={step.type === 'result_error' ? 'xmark' : 'check'} />
                </span>
                <span className="nm">{stepTitle(step)}</span>
                {step.content && <span className="rs">{String(step.content).slice(0, 18)}</span>}
              </div>
            ))}
            {isAgentThinking && (
              <div className="step pending">
                <span className="dot now"><I n="hourglass-half" /></span>
                <span className="nm">Working on it</span>
              </div>
            )}
          </div>
        )}

        {pendingActions.map(action => (
          <div className="approve" key={action.id}>
            <div className="k"><I n="hand" />Needs your approval</div>
            <div className="h">{action.description || describeToolCall(action.type, { path: action.path })}</div>
            {action.path && <div className="d">{action.path}</div>}
            {action.content && <pre>{String(action.content).slice(0, 600)}</pre>}
            <div className="b">
              <button type="button" className="n" onClick={() => onDiscard?.(action.id)}>
                <I n="xmark" style={{ fontSize: 12 }} />Skip
              </button>
              <button type="button" className="y" onClick={() => onApprove?.(action.id)}>
                <I n="check" style={{ fontSize: 12 }} />Allow
              </button>
            </div>
          </div>
        ))}

        {isTyping && (
          <div className="running">
            <I n="circle-notch" spin style={{ fontSize: 11 }} />
            Working{elapsed ? ` · ${elapsed}` : ''}
            <button type="button" className="st" onClick={onStop}>
              <I n="stop" style={{ fontSize: 10.5 }} />Stop
            </button>
          </div>
        )}
      </div>

      <div className="comp">
        <button type="button" className="att" onClick={onOpenModels} aria-label="Attach context">
          <I n="paperclip" />
        </button>
        <textarea
          rows={1}
          value={draft}
          placeholder={isTyping ? 'Add to the job…' : 'Tell Luna what to do…'}
          onChange={e => setDraft(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
        />
        <button type="button" className="go" onClick={send} disabled={!draft.trim() || isTyping} aria-label="Send">
          <I n="arrow-up" />
        </button>
      </div>

      <Sheet open={history} title="Chats" onClose={() => setHistory(false)}
        action={(
          <button type="button" className="ib" onClick={() => { onNewConversation?.(); setHistory(false); }} aria-label="New chat">
            <I n="pen-to-square" />
          </button>
        )}
      >
        <div className="list">
          {conversations.map(conv => (
            <div className="row" key={conv.id}>
              <button
                type="button"
                className="row"
                style={{ padding: 0 }}
                onClick={() => { onSwitchConversation?.(conv.id); setHistory(false); }}
              >
                <span className="tile"><I n="comment" /></span>
                <span className="tx">
                  <b>{conv.title || 'Untitled'}</b>
                  <span>{ago(conv.messages?.[conv.messages.length - 1]?.timestamp) || 'empty'}</span>
                </span>
              </button>
              <span className="end">
                <button type="button" className="ib" onClick={() => onRenameConversation?.(conv.id)} aria-label="Rename">
                  <I n="pen" />
                </button>
                <button type="button" className="ib" onClick={() => onDeleteConversation?.(conv.id)} aria-label="Delete">
                  <I n="trash-can" />
                </button>
              </span>
            </div>
          ))}
        </div>
      </Sheet>

      <Sheet open={menu} title="This chat" onClose={() => setMenu(false)}>
        <div className="list">
          <button type="button" className="row" onClick={() => { onNewConversation?.(); setMenu(false); }}>
            <span className="tile"><I n="pen-to-square" /></span>
            <span className="tx"><b>New chat</b><span>Start a fresh job</span></span>
          </button>
          <button type="button" className="row" onClick={() => { onRenameConversation?.(activeConversationId); setMenu(false); }}>
            <span className="tile"><I n="pen" /></span>
            <span className="tx"><b>Rename</b><span>{title}</span></span>
          </button>
          <button type="button" className="row" onClick={() => { onExportChat?.(activeConversationId); setMenu(false); }}>
            <span className="tile"><I n="file-arrow-down" /></span>
            <span className="tx"><b>Export</b><span>Save the transcript as text</span></span>
          </button>
          <button type="button" className="row" onClick={() => { onClearChat?.(activeConversationId); setMenu(false); }}>
            <span className="tile"><I n="eraser" /></span>
            <span className="tx"><b>Clear messages</b><span>Keep the chat, drop its history</span></span>
          </button>
        </div>
      </Sheet>

      <Sheet open={picker} title="Model" onClose={() => setPicker(false)}>
        <div className="lbl">Available now</div>
        {models.length === 0 ? (
          <div className="empty">
            <I n="cube" />
            <b>No models yet</b>
            Download one on the Models tab, or connect a cloud key.
          </div>
        ) : (
          <div className="list">
            {models.map(model => {
              const cloud = model.source === 'cloud' || model.cloud;
              return (
                <button
                  type="button"
                  className="row"
                  key={model.id}
                  onClick={() => { onSelectModel?.(model); setPicker(false); }}
                >
                  <span className="tile"><I n={cloud ? 'cloud' : 'microchip'} /></span>
                  <span className="tx">
                    <b>{model.name}</b>
                    <span>{cloud ? 'Cloud · uses your quota' : 'On device · works offline'}</span>
                  </span>
                  <span className="end">{activeModel?.id === model.id ? <I n="check" /> : null}</span>
                </button>
              );
            })}
          </div>
        )}
        <button type="button" className="btn soft wide" onClick={() => { setPicker(false); onOpenModels?.(); }}>
          <I n="cube" />Manage models
        </button>
      </Sheet>
    </div>
  );
}
