# ForgeAI

**Your local AI coding assistant**

A modular, offline-first AI coding assistant that runs with local open-source language models. Works on desktop, mobile (Android), and web.

## Features

- 💬 **Chat Interface** - Natural conversation with AI
- 📁 **File Browser** - Navigate and reference workspace files
- 🔒 **Action Approvals** - Review changes before they're applied
- 🤖 **Local Models** - Works with Ollama (localhost:11434)
- 📱 **Mobile Ready** - Android APK support with Capacitor
- 🎨 **Beautiful UI** - Dark theme with smooth animations
- 🔊 **Haptic Feedback** - Native mobile feedback

## Quick Start

### Web / Desktop

```bash
# Install dependencies
npm install

# Start development server
npm run dev

# Build for production
npm run build
```

### Android

```bash
# Install dependencies
npm install

# Initialize Capacitor (first time only)
npm run android:init

# Sync web build to Android
npm run android:sync

# Open in Android Studio
npm run android:open

# Or build APK directly
npm run android:build
```

The APK will be at: `android/app/build/outputs/apk/debug/app-debug.apk`

## Ollama Setup

ForgeAI uses Ollama for local AI inference. Install Ollama:

```bash
# macOS/Linux
curl -fsSL https://ollama.com/install.sh | sh

# Then pull a model
ollama pull llama3.1
ollama pull codellama
ollama pull smollm:latest

# Start Ollama
ollama serve
```

### Recommended Models for Mobile (4GB+ RAM)

| Model | Size | Best For |
|-------|------|----------|
| smollm:latest | ~150MB | Fast, lightweight |
| llama3.1:latest | ~1.2GB | Balanced |
| phi3:latest | ~2GB | Better quality |

## Architecture

```
forgeai/
├── src/
│   ├── components/     # React UI components
│   ├── hooks/          # React hooks (useChat, useOllama)
│   ├── styles/         # Global styles
│   ├── nativeBridge.js # Native mobile bridge
│   └── App.jsx         # Main app
├── android/            # Android project (Capacitor)
├── capacitor.config.json
└── vite.config.js
```

## Environment Variables

```bash
cp .env.example .env
```

| Variable | Default | Description |
|----------|---------|-------------|
| VITE_OLLAMA_URL | http://localhost:11434 | Ollama server URL |

## Tech Stack

- **Frontend**: React 19 + Vite
- **Animations**: Framer Motion
- **Mobile**: Capacitor 7 (Android)
- **Models**: Ollama (local)
- **File Access**: Capacitor Filesystem plugin

## Platform Support

| Platform | Status | File System | Haptics |
|----------|--------|-------------|---------|
| Web | ✅ Working | ⚠️ Limited | ❌ |
| Desktop (Tauri) | 🔜 Planned | ✅ | ❌ |
| Android | ✅ Ready | ✅ | ✅ |

## License

MIT
