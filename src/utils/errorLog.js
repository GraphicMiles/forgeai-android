const KEY = 'luna_error_log';
const MAX = 100;
export function recordError(error, context = 'app') {
  const entry = { time: new Date().toISOString(), context, message: error?.message || String(error), stack: error?.stack || '' };
  try {
    const current = JSON.parse(localStorage.getItem(KEY) || '[]');
    localStorage.setItem(KEY, JSON.stringify([entry, ...current].slice(0, MAX)));
  } catch {}
  console.error(`[Luna:${context}]`, error);
  return entry;
}
export function readErrorLog() { try { return JSON.parse(localStorage.getItem(KEY) || '[]'); } catch { return []; } }
export function clearErrorLog() { try { localStorage.removeItem(KEY); } catch {} }
export function installGlobalErrorLogging() {
  if (typeof window === 'undefined') return () => {};
  const onError = event => recordError(event.error || event.message, 'window');
  const onRejection = event => recordError(event.reason, 'unhandled-promise');
  window.addEventListener('error', onError);
  window.addEventListener('unhandledrejection', onRejection);
  return () => { window.removeEventListener('error', onError); window.removeEventListener('unhandledrejection', onRejection); };
}
