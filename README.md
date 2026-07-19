# Toddler

Train small AI models on your phone and export them.

- Stack: React 19 + Vite 6 + Capacitor 7 + FastAPI + Firebase
- Mobile build: `.github/workflows/android.yml` produces a signed APK
- Local models: TF.js MobileNet+KNN for vision, Naive Bayes for text,
  `@huggingface/transformers` for text classification,
  `@mlc-ai/web-llm` for on-device LLMs (SmolLM2 360M/1.7B, Llama 3.2 3B).
- BYOC: phones can claim queued text training jobs via Web Worker +
  foreground Android service + PARTIAL_WAKE_LOCK.
