/**
 * App-wide toast — replaces native alert() dialogs with a non-blocking,
 * auto-dismissing notification that matches the app's dark/green design language.
 *
 * Usage: showToast('Saved', 'success' | 'error' | 'info')
 */

let host = null;
let hideTimer = null;
let removeTimer = null;

export function showToast(message, tone = 'info') {
  if (typeof document === 'undefined') return;

  if (!host) {
    host = document.createElement('div');
    host.className = 'toast-host';
    document.body.appendChild(host);
  }

  host.innerHTML = '';
  const toast = document.createElement('div');
  toast.className = `toast ${tone}`;
  toast.setAttribute('role', tone === 'error' ? 'alert' : 'status');
  const text = String(message ?? '');
  toast.textContent = text.length > 400 ? `${text.slice(0, 400)}…` : text;
  host.appendChild(toast);

  setTimeout(() => toast.classList.add('visible'), 16);

  clearTimeout(hideTimer);
  clearTimeout(removeTimer);
  hideTimer = setTimeout(() => toast.classList.remove('visible'), 3600);
  removeTimer = setTimeout(() => { if (host) host.innerHTML = ''; }, 3900);
}
