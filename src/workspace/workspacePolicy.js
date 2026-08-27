/**
 * Workspace policy — the hard limits and blocklists that apply to every read
 * and write, whether it comes from the editor or from one of Luna's tools.
 * Fixed constants: a configurable policy engine was more surface than value.
 */

export const WORKSPACE_LIMITS = Object.freeze({
  uiReadBytes: 2 * 1024 * 1024,
  writeBytes: 2 * 1024 * 1024,
});

const IGNORED_DIRECTORIES = new Set([
  '.git', '.hg', '.svn', '.ssh', '.gnupg', '.aws',
  'node_modules', 'build', 'dist', 'coverage', 'target',
]);
const SENSITIVE_EXACT = new Set([
  '.env', '.netrc', '.npmrc', '.pypirc',
  'id_rsa', 'id_ed25519', 'credentials', 'credentials.json',
]);
const SENSITIVE_EXTENSIONS = ['.pem', '.key', '.p12', '.pfx', '.jks', '.keystore'];
const INTERNAL_MARKERS = ['.luna-tmp-', '.luna-old-', '.luna-trash-'];

export function isSensitiveWorkspacePath(path) {
  const parts = String(path || '').split('/').filter(Boolean).map(part => part.toLowerCase());
  return parts.some(part => (
    IGNORED_DIRECTORIES.has(part)
    || SENSITIVE_EXACT.has(part)
    || part.startsWith('.env.')
    || part.startsWith('credentials.')
    || part.startsWith('secret')
    || part.includes('private-key')
    || SENSITIVE_EXTENSIONS.some(extension => part.endsWith(extension))
  ));
}

export function isInternalWorkspacePath(path) {
  const name = String(path || '').split('/').pop()?.toLowerCase() || '';
  return INTERNAL_MARKERS.some(marker => name.includes(marker));
}

export function assertWorkspacePathAllowed(path, operation = 'access') {
  if (isInternalWorkspacePath(path)) throw new Error(`Luna internal transaction files cannot be used for ${operation}.`);
  if (isSensitiveWorkspacePath(path)) throw new Error(`Sensitive workspace paths are blocked for ${operation}.`);
  return path;
}

export function filterWorkspaceTree(nodes = []) {
  const filtered = [];
  for (const node of nodes || []) {
    if (!node?.path || isSensitiveWorkspacePath(node.path) || isInternalWorkspacePath(node.path)) continue;
    if (node.type === 'folder' || node.children) {
      filtered.push({ ...node, children: filterWorkspaceTree(node.children || []) });
    } else {
      filtered.push(node);
    }
  }
  return filtered;
}

export function utf8ByteLength(value) {
  return new TextEncoder().encode(String(value ?? '')).byteLength;
}
