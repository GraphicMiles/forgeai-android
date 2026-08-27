/** Formatting helpers shared by the four screens. */

/** Human file size. Keeps one decimal only where it earns its place. */
export function bytes(value) {
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) return '—';
  if (n < 1024) return `${n} B`;
  if (n < 1024 ** 2) return `${Math.round(n / 1024)} KB`;
  if (n < 1024 ** 3) return `${(n / 1024 ** 2).toFixed(n < 10 * 1024 ** 2 ? 1 : 0)} MB`;
  return `${(n / 1024 ** 3).toFixed(1)} GB`;
}

export function ago(ts) {
  if (!ts) return '';
  const diff = Date.now() - ts;
  if (diff < 60_000) return 'just now';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} min ago`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} h ago`;
  return new Date(ts).toLocaleDateString([], { month: 'short', day: 'numeric' });
}

export function clockOf(ts) {
  if (!ts) return '';
  return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

/** The icon has to say what the file is — brands where a brand exists. */
export function fileGlyph(name = '') {
  const ext = String(name).toLowerCase().split('.').pop();
  const map = {
    html: ['html5', true], htm: ['html5', true],
    js: ['js', true], mjs: ['js', true], cjs: ['js', true], jsx: ['react', true],
    ts: ['js', true], tsx: ['react', true],
    css: ['css3-alt', true], scss: ['css3-alt', true],
    md: ['markdown', true], markdown: ['markdown', true],
    py: ['python', true], java: ['java', true], rs: ['rust', true], php: ['php', true],
    sh: ['terminal', false], bash: ['terminal', false],
    json: ['file-code', false],
    yml: ['sliders', false], yaml: ['sliders', false],
    png: ['image', false], jpg: ['image', false], jpeg: ['image', false], gif: ['image', false],
    webp: ['image', false], svg: ['bezier-curve', false],
    pdf: ['file-pdf', false], zip: ['file-zipper', false], gguf: ['microchip', false],
    txt: ['file-lines', false], log: ['file-lines', false],
    env: ['lock', false], gitignore: ['code-branch', false],
  };
  if (String(name).toLowerCase() === 'package.json') return ['npm', true];
  if (String(name).toLowerCase() === 'package-lock.json') return ['npm', true];
  const hit = map[ext];
  if (hit) return hit;
  if (ext === 'json') return ['file-code', false];
  return ['file', false];
}
