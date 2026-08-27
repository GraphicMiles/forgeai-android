import assert from 'node:assert/strict';
import {
  assertWorkspacePathAllowed,
  filterWorkspaceTree,
  isInternalWorkspacePath,
  isSensitiveWorkspacePath,
  utf8ByteLength,
} from '../src/workspace/workspacePolicy.js';

assert.equal(isSensitiveWorkspacePath('.env'), true);
assert.equal(isSensitiveWorkspacePath('config/.env.production'), true);
assert.equal(isSensitiveWorkspacePath('.ssh/id_rsa'), true);
assert.equal(isSensitiveWorkspacePath('keys/release.jks'), true);
assert.equal(isSensitiveWorkspacePath('src/App.jsx'), false);
assert.equal(isInternalWorkspacePath('src/App.jsx.luna-tmp-123'), true);
assert.equal(isInternalWorkspacePath('src/App.jsx.luna-trash-123'), true);
assert.throws(() => assertWorkspacePathAllowed('credentials.json', 'reading'), /Sensitive/);
assert.equal(utf8ByteLength('🙂'), 4);

const tree = filterWorkspaceTree([
  { name: 'src', path: 'src', type: 'folder', children: [{ name: 'App.jsx', path: 'src/App.jsx', type: 'file' }] },
  { name: '.git', path: '.git', type: 'folder', children: [] },
  { name: '.env', path: '.env', type: 'file' },
  { name: 'temp', path: 'file.luna-old-123', type: 'file' },
]);
assert.deepEqual(tree, [
  { name: 'src', path: 'src', type: 'folder', children: [{ name: 'App.jsx', path: 'src/App.jsx', type: 'file' }] },
]);

console.log('workspace policy tests passed');
