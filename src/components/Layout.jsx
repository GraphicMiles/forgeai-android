import { MessageSquare, Boxes, Database, Settings as SettingsIcon, FolderOpen } from 'lucide-react';
import { SCREENS } from '../constants/screens.js';
import './Layout.css';

const TABS = [
  { id: SCREENS.CHAT, label: 'Chat', icon: MessageSquare },
  { id: SCREENS.ZOO, label: 'Zoo', icon: Boxes },
  { id: SCREENS.COLLECTION, label: 'Collection', icon: Database },
  { id: SCREENS.WORKSPACE, label: 'Files', icon: FolderOpen },
  { id: SCREENS.SETTINGS, label: 'Settings', icon: SettingsIcon },
];

export default function Layout({
  children,
  model = 'No model',
  status = 'idle',
  ollamaConnected = false,
  currentScreen = SCREENS.CHAT,
  onScreenChange,
  isConnecting = false,
}) {
  const statusMeta =
    status === 'busy'
      ? { tone: 'warning', label: 'Working' }
      : status === 'off'
        ? { tone: 'danger', label: isConnecting ? 'Connecting...' : 'Offline' }
        : { tone: 'active', label: ollamaConnected ? 'Ready' : 'Idle' };

  return (
    <div className="layout-root">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true" />
          <span className="brand-text">
            <span className="brand-name display">Luna</span>
            <span className="brand-sub mono">{model}</span>
          </span>
        </div>

        <div className="topbar-right">
          <div className={`status-pill ${statusMeta.tone}`} title="Model status">
            <span className="status-dot" />
            <span className="status-label mono">{statusMeta.label}</span>
          </div>
        </div>
      </header>

      <nav className="tabbar" aria-label="Primary">
        {TABS.map((tab) => {
          const Icon = tab.icon;
          const active = currentScreen === tab.id;
          return (
            <button
              key={tab.id}
              type="button"
              className={`tab ${active ? 'active' : ''}`}
              onClick={() => onScreenChange?.(tab.id)}
              aria-current={active ? 'page' : undefined}
              aria-label={`Open ${tab.label}`}
              title={tab.label}
            >
              <Icon size={20} strokeWidth={active ? 2.2 : 1.8} />
              <span className="tab-label">{tab.label}</span>
            </button>
          );
        })}
      </nav>

      <main className="layout-main">{children}</main>
    </div>
  );
}
