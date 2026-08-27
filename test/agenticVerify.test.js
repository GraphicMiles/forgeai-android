import assert from 'node:assert/strict';
import { runAgenticLoop } from '../src/agent/agenticLoop.js';

// In-memory workspace that can simulate a write NOT sticking, to prove the loop
// verifies before declaring success.
function makeWorkspace({ dropWrites = false } = {}) {
  const files = new Map();
  const folders = new Set();
  return {
    async readText(path) {
      if (!files.has(path)) throw new Error('not found: ' + path);
      return files.get(path);
    },
    async writeText(path, content) { if (!dropWrites) files.set(path, content); },
    async createFile(path) { if (!files.has(path)) files.set(path, ''); },
    async createFolder(path) { folders.add(path); },
    async inspect(path) { if (!files.has(path) && !folders.has(path)) throw new Error('missing'); return { path }; },
    _files: files,
    _folders: folders,
  };
}

// A scripted provider that emits a sequence of native tool_calls, one per turn.
function scriptedProvider(turns) {
  let i = 0;
  return {
    supportsToolUse: true,
    async loadModel() { return { loaded: true }; },
    async stream({ onToken }) {
      const turn = turns[Math.min(i, turns.length - 1)];
      i++;
      if (turn.text) onToken?.(turn.text);
      return turn.toolCalls ? { toolCalls: turn.toolCalls } : {};
    },
  };
}

const tc = (name, args, id) => ({ id: id || `c${Math.random().toString(36).slice(2, 6)}`, type: 'function', function: { name, arguments: JSON.stringify(args) } });

// --- Case 1: successful write verifies and completes ---
{
  const ws = makeWorkspace();
  const provider = scriptedProvider([
    { toolCalls: [tc('create_file', { path: 'hello.js', content: 'export const hi = 1;' })] },
    { toolCalls: [tc('respond', { message: 'Created hello.js' })] },
  ]);
  const result = await runAgenticLoop({ provider, model: {}, userMessage: 'make hello.js', workspaceProvider: ws, isNative: true });
  assert.equal(result.success, true);
  assert.equal(result.verified, true, 'file write should be verified');
  assert.equal(result.response, 'Created hello.js');
  assert.equal(ws._files.get('hello.js'), 'export const hi = 1;');
}
console.log('  ✓ verified successful write');

// --- Case 2: write that does NOT stick is caught; model gets a chance to fix ---
{
  const ws = makeWorkspace({ dropWrites: true });
  let respondedTooEarly = false;
  const provider = scriptedProvider([
    { toolCalls: [tc('create_file', { path: 'ghost.js', content: 'nope' })] },
    { toolCalls: [tc('respond', { message: 'Done!' })] }, // premature success
    { text: 'Acknowledging failure', toolCalls: [tc('respond', { message: 'Could not persist the file.' })] },
  ]);
  const result = await runAgenticLoop({ provider, model: {}, userMessage: 'make ghost.js', workspaceProvider: ws, isNative: true });
  // The loop must not have accepted the premature "Done!" since the file is missing.
  assert.notEqual(result.response, 'Done!', 'must not falsely report success when write did not stick');
  const verifyStep = result.toolCalls.find(t => t.tool === 'verify_changes');
  assert.ok(verifyStep, 'a verification step should be recorded');
  assert.equal(verifyStep.result.passed, false);
  void respondedTooEarly;
}
console.log('  ✓ caught write that did not persist');


// --- Case 4: scratchpad is injected after the first tool call ---
{
  const ws = makeWorkspace();
  let sawScratchpad = false;
  let turn = 0;
  const provider = {
    supportsToolUse: true,
    async loadModel() { return {}; },
    async stream({ messages }) {
      if (messages.some(m => m.role === 'system' && /SCRATCHPAD/.test(m.content || ''))) sawScratchpad = true;
      turn++;
      if (turn === 1) return { toolCalls: [tc('read_file', { path: 'hello.js' })] };
      return { toolCalls: [tc('respond', { message: 'done' })] };
    },
  };
  ws._files.set('hello.js', 'x');
  await runAgenticLoop({ provider, model: {}, userMessage: 'read hello.js', workspaceProvider: ws, isNative: true });
  assert.equal(sawScratchpad, true, 'scratchpad should be injected once progress exists');
}
console.log('  ✓ scratchpad injected into loop');

// --- Case 5: create_file auto-creates parent folders (project/index.html) ---
{
  const ws = makeWorkspace();
  const provider = scriptedProvider([
    { toolCalls: [tc('create_folder', { path: 'project' })] },
    { toolCalls: [tc('create_file', { path: 'project/index.html', content: '<!DOCTYPE html>' })] },
    { toolCalls: [tc('respond', { message: 'Built the site in project/' })] },
  ]);
  const result = await runAgenticLoop({ provider, model: {}, userMessage: 'build a website and create a folder project', workspaceProvider: ws, isNative: true });
  assert.equal(result.success, true);
  assert.ok(ws._folders.has('project'), 'folder should be created');
  assert.equal(ws._files.get('project/index.html'), '<!DOCTYPE html>');
}
console.log('  ✓ folder + nested file creation');

// --- Case 6: create_file on an existing file overwrites instead of hard-failing ---
{
  const ws = makeWorkspace();
  ws._files.set('index.html', 'OLD');
  const provider = scriptedProvider([
    { toolCalls: [tc('create_file', { path: 'index.html', content: 'NEW' })] },
    { toolCalls: [tc('respond', { message: 'Updated index.html' })] },
  ]);
  const result = await runAgenticLoop({ provider, model: {}, userMessage: 'create index.html', workspaceProvider: ws, isNative: true });
  assert.equal(result.success, true);
  assert.equal(ws._files.get('index.html'), 'NEW', 'existing file should be overwritten, not error');
  const createStep = result.toolCalls.find(t => t.tool === 'create_file');
  assert.equal(createStep.result.success, true);
  assert.equal(createStep.result.overwritten, true);
}
console.log('  ✓ create_file overwrites existing instead of failing');

// --- Case 7: large file read returns an outline (skeleton), not full body ---
{
  const big = 'import x from "y";\n' + Array.from({ length: 120 }, (_, i) => `export function fn${i}(a,b){ return a+b+${i}; }`).join('\n');
  const ws = makeWorkspace();
  ws._files.set('big.js', big);
  let turn = 0;
  const provider = {
    supportsToolUse: true,
    async loadModel() { return {}; },
    async stream() {
      turn++;
      if (turn === 1) return { toolCalls: [tc('read_file', { path: 'big.js' })] };
      return { toolCalls: [tc('respond', { message: 'done' })] };
    },
  };
  const r = await runAgenticLoop({ provider, model: {}, userMessage: 'read big.js and summarize it', workspaceProvider: ws, isNative: true });
  const readStep = r.toolCalls.find(t => t.tool === 'read_file');
  assert.ok(readStep.result.outline, 'large file should return an outline');
  assert.ok(!readStep.result.content, 'outline mode should not return full content');
  assert.ok(readStep.result.symbols.includes('fn0'), 'outline lists symbols');
}
console.log('  ✓ large file read returns skeleton outline');

// --- Case 8: read_symbol returns just one function body ---
{
  const src = 'export function alpha(){ return 1; }\nexport function beta(){ const z = 2; return z; }\n';
  const ws = makeWorkspace();
  ws._files.set('mod.js', src);
  let turn = 0;
  const provider = {
    supportsToolUse: true,
    async loadModel() { return {}; },
    async stream() {
      turn++;
      if (turn === 1) return { toolCalls: [tc('read_symbol', { path: 'mod.js', symbol: 'beta' })] };
      return { toolCalls: [tc('respond', { message: 'done' })] };
    },
  };
  const r = await runAgenticLoop({ provider, model: {}, userMessage: 'show me the beta function in mod.js', workspaceProvider: ws, isNative: true });
  const step = r.toolCalls.find(t => t.tool === 'read_symbol');
  assert.equal(step.result.success, true);
  assert.match(step.result.content, /const z = 2/);
  assert.ok(!/alpha/.test(step.result.content), 'read_symbol should isolate one function');
}
console.log('  ✓ read_symbol isolates one function');

// --- Case 9: only relevant tools are sent to the provider (subsetting) ---
{
  const ws = makeWorkspace();
  let toolsSeen = null;
  const provider = {
    supportsToolUse: true,
    async loadModel() { return {}; },
    async stream({ tools }) {
      toolsSeen = (tools || []).map(t => t.function.name);
      return { toolCalls: [tc('respond', { message: 'hi' })] };
    },
  };
  await runAgenticLoop({ provider, model: {}, userMessage: 'show me package.json', workspaceProvider: ws, isNative: true });
  assert.ok(toolsSeen.includes('read_file'), 'read request exposes read_file');
  assert.ok(toolsSeen.includes('respond'), 'control tools always present');
  assert.ok(!toolsSeen.includes('git_push'), 'irrelevant git tools filtered out');
}
console.log('  ✓ dynamic tool subsetting');

console.log('agentic verify tests passed');
