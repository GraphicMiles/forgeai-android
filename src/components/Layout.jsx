import { SCREENS } from '../constants/screens.js';
import { I } from './luna/ui.jsx';

// Four tabs. The icon has to say what the tab is: a speech bubble for the job,
// a folder for the files, a cube for the models, sliders for preferences.
const TABS = [
  { id: SCREENS.CHAT, label: 'Chat', icon: 'comment' },
  { id: SCREENS.FILES, label: 'Files', icon: 'folder' },
  { id: SCREENS.MODELS, label: 'Models', icon: 'cube' },
  { id: SCREENS.SETTINGS, label: 'Settings', icon: 'sliders' },
];

export default function Layout({ children, currentScreen = SCREENS.CHAT, onScreenChange }) {
  return (
    <div className="app">
      {children}
      <nav className="tabs" aria-label="Primary">
        {TABS.map(tab => (
          <button
            key={tab.id}
            type="button"
            className={currentScreen === tab.id ? 'on' : ''}
            onClick={() => onScreenChange?.(tab.id)}
            aria-current={currentScreen === tab.id ? 'page' : undefined}
          >
            <I n={tab.icon} />
            {tab.label}
          </button>
        ))}
      </nav>
    </div>
  );
}
