# ForgeAI

**Your local AI coding assistant**

A modular, offline-first AI coding assistant that runs with local open-source language models. Built with React, Vite, and Firebase.

## Features

- 💬 **Chat Interface** - Natural conversation with AI
- 📁 **File Browser** - Navigate and reference workspace files
- 🔒 **Action Approvals** - Review changes before they're applied
- 🤖 **Local Models** - Works with Ollama, llama.cpp, LM Studio
- 🎨 **Beautiful UI** - Dark theme with smooth animations

## Quick Start

```bash
# Install dependencies
npm install

# Start development server
npm run dev

# Build for production
npm run build
```

## Configuration

Copy `.env.example` to `.env` and add your Firebase credentials:

```bash
cp .env.example .env
```

## Architecture

```
src/
├── components/     # UI components
│   ├── ChatContainer.jsx
│   ├── Message.jsx
│   ├── FilePanel.jsx
│   └── ActionCard.jsx
├── hooks/          # React hooks
│   └── useChat.js
├── styles/         # Global styles
└── App.jsx         # Main app
```

## Tech Stack

- **Frontend**: React 19 + Vite
- **Animations**: Framer Motion
- **Backend**: Firebase (Firestore, Auth)
- **Models**: Ollama / web-llm (local)

## License

MIT
