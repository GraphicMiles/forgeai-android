# Toddler

> Upload a spreadsheet. Walk away with a classifier.

Toddler is a no-code AI training platform for domain experts. Drop in a CSV (or an
image folder), map your columns, and get back a trained model you can call from a
chat UI, export as a `.pkl`, or ship to an Android device for on-device inference.

## Stack

- **Frontend:** React 19 + Vite + Tailwind v4, React Router, Framer Motion, Chart.js, react-hot-toast.
- **Web ML:** TensorFlow.js + MobileNet + KNN classifier (vision), @huggingface/transformers (mobile NLP).
- **Backend:** FastAPI + scikit-learn + pandas, served with uvicorn.
- **Infra:** Firebase Auth + Firestore, Cloudinary (model weights & dataset blobs), Capacitor 7 (Android shell), Tauri (desktop agent, experimental), GitHub Actions (APK builds).

## Repo layout

```
src/                React web app
  App.jsx           Router + auth gate (web vs. native branching)
  LandingPage.jsx   Marketing landing page
  Auth.jsx          Sign up / sign in (email + Google)
  Onboarding.jsx    3-step create-project wizard
  Dashboard.jsx     Web/desktop dashboard (overview / batch / chat / dev)
  MobileDashboard.jsx  Android on-device model zoo + local inference
  visionML.js       TF.js MobileNet+KNN trainer/predictor/retrainer
  cloud.js          Cloudinary upload/fetch helpers
  firebase.js       Firebase init
backend/            FastAPI service
  main.py           REST API + CORS + model catalog + signed uploads
  agent.py          BYOC training agent (Firestore queue listener)
  agent_entry.py    Entrypoint that loads credentials from env
  run.sh            Starts agent + uvicorn together
  hardware_audit.py CUDA/MPS/CPU detection
android/            Capacitor Android project
desktop/            Experimental Tauri desktop agent
public/widget.js    Embeddable chat widget
.github/workflows/  CI (APK build, keystore helpers)
```

## Quickstart (web)

```bash
cp .env.example .env   # fill in Firebase, Cloudinary, VITE_API_URL
npm install
npm run dev            # http://localhost:5173
```

## Backend

```bash
cd backend
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# Run locally (expects FIREBASE_SERVICE_ACCOUNT_JSON to be set)
./run.sh               # starts agent + FastAPI on http://0.0.0.0:7860
```

The backend exposes:

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | Health check |
| POST | `/train` | Queue a text training job |
| GET | `/jobs/{id}` | Poll job status/progress |
| POST | `/predict` | Run inference against a trained model |
| POST | `/batch` | Bulk-classify a CSV |
| GET | `/projects/{id}/download` | Download `.pkl` artifact |
| GET | `/projects/{id}/export` | Download zipped model + helper script |
| GET | `/models` | Model catalog for Android |
| POST | `/uploads/sign` | Signed Cloudinary upload (authenticated) |
| GET/POST | `/datasets` | Per-user dataset registry (authenticated) |
| DELETE | `/projects/{id}` / `/account` | Project / account deletion |

### Authentication

Web requests from a signed-in user send a Firebase ID token as a
`Authorization: Bearer <token>` header. Predictions also work with an
`X-API-Key: tdlr_live_…` header (generated on first training).

## Android

See [`README_ANDROID.md`](./README_ANDROID.md). In short:

```bash
npm install
npm run build
npx cap sync android
npx cap open android
```

A debug APK is built on every push to `main` by `.github/workflows/android.yml`
and uploaded as a workflow artifact.

## Environment variables

See `.env.example` for the full list. The Firebase web config and
`VITE_API_URL` are consumed by Vite at build time. The backend expects
`FIREBASE_SERVICE_ACCOUNT_JSON`, `CLOUDINARY_CLOUD_NAME`, `CLOUDINARY_API_KEY`,
`CLOUDINARY_API_SECRET`, and `CLOUDINARY_SIGNED_UPLOAD_PRESET` at runtime.

## Deploying

- **Frontend:** Push to Vercel (root of repo, build command `npm run build`, output `dist`). `vercel.json` already rewrites all routes to `index.html`.
- **Backend:** Deploy `backend/` to Render / any Docker host using `backend/Dockerfile`. Expose port 7860. Point `VITE_API_URL` at the deployed URL.

## License

© 2026 Toddler. All rights reserved.
