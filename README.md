# Toddler

**Train your own AI. Own it. Sell it.**

Browse the Model Zoo, pick an open-source LLM or vision model, train it on your own data using your own device, and optionally list it on the Marketplace for others to discover.

## What Toddler Is

- **Model Zoo** — Curated catalog of trainable AI models (SmolLM2, Llama, MobileNet, YOLO), filtered by what your device can handle
- **Training Engine** — On-device RAG context injection and LoRA fine-tuning. Data never leaves your device.
- **Marketplace** — Publish your trained models. Others can clone and use them. Free in v1.

## Quick Start

```bash
npm install
npm run build
npx cap sync android
```

## Stack

- **Frontend:** React 19 + Vite 6 + Tailwind v4
- **Mobile:** Capacitor 7 (Android) with Kotlin foreground services
- **Desktop:** Tauri (Rust/Node) — early stage
- **Backend:** FastAPI + Firebase (Firestore, Auth)
- **LLM Runtime:** @mlc-ai/web-llm (on-device via WebGPU)
- **Storage:** Cloudinary (signed uploads), Firestore (metadata), IndexedDB (local RAG chunks)
- **CI/CD:** GitHub Actions → signed APK

## Model Catalog

| Model | Params | Size | Min RAM | Platform | Task |
|---|---|---|---|---|---|
| SmolLM2 360M | 360M | 150MB | 4GB | 📱💻☁️ | Chat |
| SmolLM2 1.7B | 1.7B | 900MB | 6GB | 💻☁️ | Chat |
| Llama 3.2 3B | 3.2B | 1.7GB | 8GB | 💻☁️ | Chat + Code |
| Qwen 2.5 1.5B | 1.5B | 800MB | 6GB | 💻☁️ | Chat |
| Phi-3 Mini | 3.8B | 2.2GB | 8GB | 💻☁️ | Chat |
| MobileNet V3 | — | 11MB | 2GB | 📱💻☁️ | Vision |
| YOLOv8 Nano | — | 6MB | 2GB | 📱💻☁️ | Detection |

## Training Modes

### RAG (Any Device)
Upload your documents → model reads them at inference time. Instant. No GPU needed.

### LoRA Fine-Tune (Desktop / Cloud)
Upload prompt/completion pairs → model weights are actually updated. Needs GPU.

### Vision Transfer Learning (Phase 2)
Upload labeled image folders → vision model learns your categories.

## Architecture

```
Web Dashboard (toddler.ai)     ← Browse Zoo, manage models, marketplace
        │
        ├── 📱 Phone (BYOC)    ← Train SmolLM2 360M/1.7B, RAG chat
        ├── 💻 Desktop (BYOC)  ← Train Llama 3B, LoRA fine-tune
        └── ☁️ Cloud (Pro)     ← Train anything, hosted inference
                │
                ▼
        FastAPI Backend (Render)
                │
                ▼
        Firebase (Auth + Firestore)
        Cloudinary (storage)
```

## Docs

- `TODDLER_SPEC.md` — Full product specification and roadmap
- `FULL_AUDIT_REPORT.md` — Complete codebase audit
- `README_ANDROID.md` — Android build guide

## License

Proprietary. © 2026 Toddler. Lagos, NG.
