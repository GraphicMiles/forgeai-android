import assert from 'node:assert/strict';

const store = new Map();
globalThis.localStorage = {
  getItem: k => store.get(k) ?? null,
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: k => store.delete(k),
};

const { createToolPolicy, isMutatingTool, describeToolCall } = await import('../src/agent/toolPolicy.js');
const { runAgenticLoop } = await import('../src/agent/agenticLoop.js');

// ---------- classification ----------
for (const tool of ['read_file', 'list_files', 'search_code', 'git_status', 'respond', 'ask_user']) {
  assert.equal(isMutatingTool(tool), false, `${tool} should be read-only`);
}
for (const tool of ['write_file', 'create_file', 'delete_file', 'run_terminal', 'git_push']) {
  assert.equal(isMutatingTool(tool), true, `${tool} should be mutating`);
}
console.log('  ✓ tool classification');

// ---------- policy ----------
const attended = createToolPolicy({ unattended: false });
assert.equal(attended('read_file'), 'allow');
assert.equal(attended('write_file'), 'approve');
const unattended = createToolPolicy({ unattended: true });
assert.equal(unattended('write_file'), 'allow');
assert.equal(unattended('run_terminal'), 'allow');
console.log('  ✓ policy decisions');

// ---------- descriptions are concrete ----------
assert.match(describeToolCall('create_file', { path: 'a/b.js' }), /a\/b\.js/);
assert.match(describeToolCall('run_terminal', { command: 'npm test' }), /npm test/);
console.log('  ✓ approval descriptions');

// ---------- the loop honours approval ----------
function scriptedProvider(outputs) {
  let i = 0;
  return {
    supportsToolUse: false,
    async stream({ onToken }) {
      const out = outputs[Math.min(i++, outputs.length - 1)];
      onToken(out);
      return { content: out };
    },
  };
}

const written = new Map();
const workspaceProvider = {
  async readText(path) {
    if (!written.has(path)) throw new Error('missing');
    return written.get(path);
  },
  async writeText(path, content) { written.set(path, content); },
  async createFile(path) { if (!written.has(path)) written.set(path, ''); },
  async inspect(path) { if (!written.has(path)) throw new Error('missing'); return { path }; },
};

// 1) Declined: the file is never written and the model is told why.
const declineAsks = [];
let result = await runAgenticLoop({
  provider: scriptedProvider([
    '```tool_call\n{"tool":"create_file","args":{"path":"note.txt","content":"hello"}}\n```',
    '```tool_call\n{"tool":"respond","args":{"message":"I did not create it."}}\n```',
  ]),
  model: {},
  userMessage: 'make note.txt',
  workspaceProvider,
  toolPolicy: createToolPolicy({ unattended: false }),
  requestApproval: async (call) => { declineAsks.push(call); return false; },
});
assert.equal(declineAsks.length, 1);
assert.equal(declineAsks[0].tool, 'create_file');
assert.match(declineAsks[0].description, /note\.txt/);
assert.equal(written.has('note.txt'), false);
assert.equal(result.toolCalls[0].result.declined, true);
console.log('  ✓ declined tool call is not executed');

// 2) Approved: the same call goes through.
result = await runAgenticLoop({
  provider: scriptedProvider([
    '```tool_call\n{"tool":"create_file","args":{"path":"note.txt","content":"hello"}}\n```',
    '```tool_call\n{"tool":"respond","args":{"message":"Created it."}}\n```',
  ]),
  model: {},
  userMessage: 'make note.txt',
  workspaceProvider,
  toolPolicy: createToolPolicy({ unattended: false }),
  requestApproval: async () => true,
});
assert.equal(written.get('note.txt'), 'hello');
assert.equal(result.success, true);
console.log('  ✓ approved tool call executes');

// 3) Read-only tools never prompt, even when execution is attended.
let prompted = 0;
await runAgenticLoop({
  provider: scriptedProvider([
    '```tool_call\n{"tool":"read_file","args":{"path":"note.txt"}}\n```',
    '```tool_call\n{"tool":"respond","args":{"message":"It says hello."}}\n```',
  ]),
  model: {},
  userMessage: 'read note.txt',
  workspaceProvider,
  toolPolicy: createToolPolicy({ unattended: false }),
  requestApproval: async () => { prompted++; return true; },
});
assert.equal(prompted, 0);
console.log('  ✓ read-only tools run without approval');

delete globalThis.localStorage;
console.log('tool policy suite passed');
