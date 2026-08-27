/**
 * Agentic Loop — Multi-Step Tool-Use Engine
 * 
 * This is the core architecture that makes Luna behave like Claude Code / Arena AI.
 * Instead of single-pass generation with regex routing, the model:
 * 
 * 1. Receives tool schemas + full conversation context
 * 2. Decides which tool to call (or respond directly)
 * 3. Executes the tool and gets results
 * 4. Reads results and decides next action
 * 5. Repeats until it calls "respond" or hits max iterations
 * 
 * Supports: read → plan → edit → verify → fix → respond cycles.
 */

import { formatToolSchemasForPrompt, parseToolCalls, extractNonToolText, streamableText, toOpenAITools, normalizeNativeToolCalls, parseLlamaFunctionSyntax } from './toolSchemas.js';
import { Scratchpad } from './scratchpad.js';
import { extractSkeleton, shouldUseSkeleton, extractSymbolBody } from './codeSkeleton.js';
import { compactToolResults, selectRelevantTools } from './tokenBudget.js';
import { performOnlineResearch } from './onlineResearch.js';
import { describeToolCall } from './toolPolicy.js';
import {
  gitClone, gitStatus, gitCommit, gitPush, gitLog,
  runTerminalCommand,
} from '../nativeBridge.js';

const MAX_ITERATIONS = 12;

// Actionable message the model can relay to the user when no workspace folder is
// selected — instead of a bare "No workspace selected." dead-end.
const NO_WORKSPACE_MSG = 'No workspace folder is selected. Open the Files tab and choose a workspace folder first (the app needs a folder to read from and write into), then ask again.';

/**
 * Create a tool executor bound to a workspace provider and native bridge.
 */
function createToolExecutor(workspaceProvider, options = {}) {
  const { isNative = false, _onToolCall, _signal } = options;

  return async function executeTool(toolName, args) {
    try {
      switch (toolName) {
        case 'read_file': {
          if (!workspaceProvider?.readText) throw new Error(NO_WORKSPACE_MSG);
          const content = await workspaceProvider.readText(args.path);
          // Token saver: for large files, return an OUTLINE (imports + signatures)
          // unless the model explicitly asked for the full body. It can then call
          // read_symbol to fetch just the function it needs.
          if (args.full !== true && shouldUseSkeleton(content)) {
            const { skeleton, lines, symbols } = extractSkeleton(content);
            return { success: true, path: args.path, outline: skeleton, lines, symbols,
              note: 'Large file returned as an outline to save tokens. Use read_symbol {path, symbol} for a specific function, or read_file {path, full:true} for the whole file.' };
          }
          return { success: true, path: args.path, content, lines: content.split('\n').length };
        }

        case 'read_symbol': {
          if (!workspaceProvider?.readText) throw new Error(NO_WORKSPACE_MSG);
          const content = await workspaceProvider.readText(args.path);
          const body = extractSymbolBody(content, args.symbol);
          if (body === null) return { success: false, path: args.path, symbol: args.symbol, error: `Symbol "${args.symbol}" not found in ${args.path}.` };
          return { success: true, path: args.path, symbol: args.symbol, content: body, lines: body.split('\n').length };
        }

        case 'write_file': {
          if (!workspaceProvider?.writeText) throw new Error(NO_WORKSPACE_MSG);
          await workspaceProvider.writeText(args.path, args.content);
          // Verify
          const verified = await workspaceProvider.readText(args.path);
          const match = verified === args.content;
          return { success: match, path: args.path, verified: match, lines: args.content.split('\n').length };
        }

        case 'create_file': {
          if (!workspaceProvider?.createFile) throw new Error(NO_WORKSPACE_MSG);
          await ensureParentFolders(workspaceProvider, args.path);
          let existed = false;
          try { await workspaceProvider.inspect(args.path); existed = true; } catch { /* doesn't exist, good */ }
          if (existed && !args.overwrite) {
            // Don't hard-fail: writing the content is almost always the intent.
            // Overwrite and report it, so "create index.html" twice just updates it.
            await workspaceProvider.writeText(args.path, args.content);
            return { success: true, path: args.path, created: false, overwritten: true, note: 'File already existed; updated its content.', lines: args.content.split('\n').length };
          }
          if (!existed) await workspaceProvider.createFile(args.path);
          await workspaceProvider.writeText(args.path, args.content);
          return { success: true, path: args.path, created: !existed, overwritten: existed, lines: args.content.split('\n').length };
        }

        case 'create_folder': {
          if (!workspaceProvider?.createFolder) throw new Error(NO_WORKSPACE_MSG);
          try { await workspaceProvider.inspect(args.path); return { success: true, path: args.path, created: false, note: 'Folder already exists.' }; }
          catch { /* doesn't exist */ }
          await ensureParentFolders(workspaceProvider, args.path);
          await workspaceProvider.createFolder(args.path);
          return { success: true, path: args.path, created: true };
        }

        case 'delete_file': {
          if (!workspaceProvider?.delete) throw new Error(NO_WORKSPACE_MSG);
          await workspaceProvider.delete(args.path);
          return { success: true, path: args.path, deleted: true };
        }

        case 'list_files': {
          if (!workspaceProvider?.list) {
            // Fallback: use workspace tree if available
            return { success: true, files: [], note: 'Workspace listing not available in this context.' };
          }
          const result = await workspaceProvider.list('');
          const files = flattenTree(result.items || []);
          const pattern = args.pattern;
          const filtered = pattern ? files.filter(f => matchGlob(f, pattern)) : files;
          return { success: true, files: filtered.slice(0, 200), total: filtered.length };
        }

        case 'search_code': {
          if (!workspaceProvider?.readText) throw new Error(NO_WORKSPACE_MSG);
          const query = args.query;
          const results = [];
          // Search through files (limited to first 50 for performance)
          const fileList = options.workspaceFiles || [];
          for (const file of fileList.slice(0, 50)) {
            try {
              const content = await workspaceProvider.readText(file);
              if (content.toLowerCase().includes(query.toLowerCase())) {
                const lines = content.split('\n');
                const matches = lines
                  .map((line, i) => ({ line: i + 1, text: line.trim() }))
                  .filter(l => l.text.toLowerCase().includes(query.toLowerCase()))
                  .slice(0, 5);
                if (matches.length > 0) {
                  results.push({ file, matches });
                }
              }
            } catch { /* skip unreadable files */ }
          }
          return { success: true, query, results: results.slice(0, 20), totalMatches: results.length };
        }

        case 'run_terminal': {
          if (!isNative) {
            return { success: false, output: 'Terminal requires Android native mode.', simulated: true };
          }
          try {
            const result = await runTerminalCommand({
              command: args.command,
              cwd: args.cwd || localStorage.getItem('luna_last_git_repo') || '',
              timeoutSeconds: 120,
            });
            const output = result?.output || result?.text || '';
            return { success: true, command: args.command, output: output.slice(0, 8000) };
          } catch (error) {
            return { success: false, command: args.command, error: error.message };
          }
        }

        case 'search_web': {
          try {
            const research = await performOnlineResearch(args.query);
            return {
              success: true,
              query: args.query,
              sources: research.items.slice(0, 5).map(item => ({
                title: item.title,
                url: item.url,
                snippet: item.snippet,
                publisher: item.publisher,
              })),
            };
          } catch (error) {
            return { success: false, query: args.query, error: error.message };
          }
        }

        case 'fetch_page': {
          try {
            const { researchProvider } = await import('../research/ResearchProvider.js');
            const result = await researchProvider.fetchFullPage(args.url);
            return {
              success: !result.simulated || !!result.content,
              url: args.url,
              content: (result.content || '').slice(0, 8000),
              simulated: result.simulated || false,
            };
          } catch (error) {
            return { success: false, url: args.url, error: error.message };
          }
        }

        case 'git_clone': {
          if (!isNative) return { success: false, error: 'Git requires Android native mode.' };
          try {
            const result = await gitClone(args.url, args.branch || '');
            try { localStorage.setItem('luna_last_git_repo', result.path); } catch {}
            return { success: true, path: result.path, url: args.url };
          } catch (error) {
            return { success: false, url: args.url, error: error.message };
          }
        }

        case 'git_status': {
          if (!isNative) return { success: false, error: 'Git requires Android native mode.' };
          const path = localStorage.getItem('luna_last_git_repo') || '';
          if (!path) return { success: false, error: 'No repository cloned.' };
          try {
            const result = await gitStatus(path);
            return { success: true, ...result };
          } catch (error) {
            return { success: false, error: error.message };
          }
        }

        case 'git_commit': {
          if (!isNative) return { success: false, error: 'Git requires android native mode.' };
          const path = localStorage.getItem('luna_last_git_repo') || '';
          if (!path) return { success: false, error: 'No repository cloned.' };
          try {
            const result = await gitCommit(path, args.message || 'Luna change', 'Luna User', 'luna@localhost');
            return { success: true, ...result };
          } catch (error) {
            return { success: false, error: error.message };
          }
        }

        case 'git_push': {
          if (!isNative) return { success: false, error: 'Git requires android native mode.' };
          const path = localStorage.getItem('luna_last_git_repo') || '';
          if (!path) return { success: false, error: 'No repository cloned.' };
          try {
            const result = await gitPush(path, args.force || false);
            return { success: true, ...result };
          } catch (error) {
            return { success: false, error: error.message };
          }
        }

        case 'git_diff': {
          if (!isNative) return { success: false, error: 'Git requires android native mode.' };
          const path = localStorage.getItem('luna_last_git_repo') || '';
          if (!path) return { success: false, error: 'No repository cloned.' };
          try {
            const result = await runTerminalCommand({ command: 'git diff', cwd: path, timeoutSeconds: 30 });
            return { success: true, diff: (result?.output || '').slice(0, 8000) };
          } catch (error) {
            return { success: false, error: error.message };
          }
        }

        case 'git_log': {
          if (!isNative) return { success: false, error: 'Git requires android native mode.' };
          const path = localStorage.getItem('luna_last_git_repo') || '';
          if (!path) return { success: false, error: 'No repository cloned.' };
          try {
            const result = await gitLog(path, args.count || 10);
            return { success: true, ...result };
          } catch (error) {
            return { success: false, error: error.message };
          }
        }

        case 'ask_user': {
          return { success: true, question: args.question, awaitingUserInput: true };
        }

        case 'respond': {
          return { success: true, finalResponse: args.message, done: true };
        }

        default:
          return { success: false, error: `Unknown tool: ${toolName}` };
      }
    } catch (error) {
      return { success: false, error: error.message };
    }
  };
}

/**
 * The main agentic loop.
 * 
 * @param {Object} options
 * @param {Object} options.provider - Model provider with .stream()
 * @param {Object} options.model - Active model
 * @param {string} options.userMessage - The user's message
 * @param {Array} options.history - Conversation history
 * @param {Object} options.workspaceProvider - Workspace file operations
 * @param {boolean} options.isNative - Running on Android
 * @param {Function} options.onToken - Streaming token callback
 * @param {Function} options.onToolCall - Called when a tool is invoked
 * @param {Function} options.onIteration - Called each loop iteration
 * @param {AbortSignal} options.signal - Abort controller signal
 * @param {Array} options.workspaceFiles - List of file paths in workspace
 */
export async function runAgenticLoop({
  provider,
  model,
  userMessage,
  history = [],
  workspaceProvider,
  isNative = false,
  onToken,
  onToolCall,
  onIteration,
  signal,
  workspaceFiles = [],
  // Per-tool gate: (toolName) => 'allow' | 'approve'. Defaults to allow-all so
  // callers that don't care (tests, read-only flows) are unaffected.
  toolPolicy,
  // Async: ({ tool, args, description }) => boolean. Called only for tool calls
  // the policy marks 'approve'. Returning false feeds the refusal back to the
  // model so it can adapt instead of silently failing.
  requestApproval,
}) {
  const executeTool = createToolExecutor(workspaceProvider, { isNative, onToolCall, signal, workspaceFiles });
  const currentDate = new Date().toISOString().split('T')[0];

  // Capable providers (cloud/Ollama) get real function-calling: structured tool
  // schemas in the request body and structured tool_calls back. Small on-device
  // models fall back to the prompt-embedded ```tool_call convention.
  //
  // CRITICAL: when using native tools we must NOT also inject the prompt-based
  // convention — doing both makes the model emit a hybrid that the provider's
  // tool parser rejects ("tool ... not in request.tools"). Each mode gets its
  // own system prompt.
  const useNativeTools = provider?.supportsToolUse === true && typeof toOpenAITools === 'function';
  // Token saver: expose only the tools plausibly needed for this request instead
  // of re-sending all 19 schemas every iteration. Falls back to the full set for
  // safety (e.g. multi-intent workflows). Control tools (respond/ask_user) are
  // always retained by selectRelevantTools.
  const allTools = toOpenAITools();
  const allToolNames = allTools.map(t => t.function.name);
  const relevantNames = selectRelevantTools(userMessage, allToolNames);
  const nativeTools = useNativeTools
    ? (relevantNames ? allTools.filter(t => relevantNames.includes(t.function.name)) : allTools)
    : undefined;

  const identity = `You are Luna, a local utility agent that runs natively on the user's Android device.

You are not a chat window with tools bolted on: the device is your workplace. You act directly on the
user's own files through a folder they granted you, on an app-private sandbox shell, on Git repositories,
and on the web. Everything you do happens on this phone.

Work like a careful engineer with someone looking over your shoulder:
- Prefer doing the work over describing it. If a tool can answer the question, use the tool.
- Read before you write. Never guess a file's contents.
- Write COMPLETE file content — never partial diffs or "... rest unchanged ...".
- Take one step at a time: call one tool, read its result, then decide the next action.
- If a command fails, read the error and try a different approach instead of repeating it.
- Verify what you changed before you claim it worked.
- Mutating actions may require the user's approval. If one is declined, do not retry it — say what you
  would have done, or offer another route.
- If the request is genuinely ambiguous, ask with ask_user rather than guessing.
- When the work is finished, call respond with a short, concrete summary of what actually happened.`;

  const commonGuidelines = `Current date: ${currentDate}

${identity}`;

  const systemPrompt = useNativeTools
    ? `${commonGuidelines}

Use the provided function tools to take actions. Do not describe tool calls in text or wrap them in code fences — invoke them through the tool-calling interface only.`
    : `${commonGuidelines}

${formatToolSchemasForPrompt()}`;

  // Build the conversation messages for the model
  const modelMessages = [
    { role: 'system', content: systemPrompt },
  ];

  // Add relevant history (last 8 turns)
  const relevantHistory = history.slice(-8);
  for (const msg of relevantHistory) {
    if (msg.role === 'user' || msg.role === 'assistant') {
      modelMessages.push({ role: msg.role, content: msg.content });
    }
  }

  // Add the current user message
  modelMessages.push({ role: 'user', content: userMessage });

  const toolResults = [];
  let iteration = 0;
  let finalResponse = '';
  // Track files the agent wrote/created so we can verify them before "done".
  const writtenFiles = new Map(); // path -> expected content
  let verifiedOnce = false;
  // Bounded scratchpad: hidden working memory injected each turn so the model
  // tracks long-horizon progress without re-deriving state.
  const scratchpad = new Scratchpad(userMessage);

  while (iteration < MAX_ITERATIONS) {
    iteration++;
    if (signal?.aborted) break;

    onIteration?.({ iteration, maxIterations: MAX_ITERATIONS, toolCalls: toolResults.length });

    // Stream model response. The scratchpad is injected as a fresh trailing
    // system note each turn (never appended to history, so it can't accumulate).
    let output = '';
    let streamResult;
    let lastShown = '';
    const scratchNote = scratchpad.toPrompt();
    // Token saver: roll up OLD tool results (keep the last few verbatim) so a
    // long multi-step task doesn't carry every full result forever. The
    // scratchpad preserves the important outcomes, so this is safe.
    const compactedHistory = compactToolResults(modelMessages, { keepFull: 3 });
    const turnMessages = scratchNote
      ? [...compactedHistory, { role: 'system', content: scratchNote }]
      : compactedHistory;
    try {
      streamResult = await provider.stream({
        model,
        signal,
        messages: turnMessages,
        tools: nativeTools,
        onToken: (token) => {
          output += token;
          // Only surface text that can't still turn out to be a (prompt-based)
          // tool call: everything before the first ``` fence. This prevents raw
          // tool JSON from flashing into the UI mid-stream.
          const safe = streamableText(output);
          if (safe && safe !== lastShown) {
            lastShown = safe;
            onToken?.(safe);
          }
        },
      });
    } catch (error) {
      if (error.name === 'AbortError') break;
      throw error;
    }

    // The model tried to call a tool that doesn't exist (invented a name).
    // Feed back the valid tool list and let it retry, instead of dead-ending.
    if (streamResult?.toolError && !Array.isArray(streamResult.toolCalls)) {
      const valid = toOpenAITools().map(t => t.function.name).join(', ');
      modelMessages.push({ role: 'assistant', content: String(streamResult.failedGeneration || '').slice(0, 500) || '(invalid tool call)' });
      modelMessages.push({ role: 'user', content: `That tool call was invalid: ${streamResult.toolError}. Only use these tools: ${valid}. Retry using one of them, or use respond to answer directly.` });
      scratchpad.addObservation(`invalid tool call rejected: ${streamResult.toolError}`);
      continue;
    }

    // Prefer native tool_calls; fall back to parsing the text output.
    const nativeCalls = Array.isArray(streamResult?.toolCalls)
      ? normalizeNativeToolCalls(streamResult.toolCalls)
      : [];
    // Prefer native tool_calls; fall back to the ```tool_call parser, then to
    // Llama's <function=...> syntax (which some models emit in plain content).
    const toolCalls = nativeCalls.length > 0
      ? nativeCalls
      : (parseToolCalls(output).length > 0 ? parseToolCalls(output) : parseLlamaFunctionSyntax(output));
    const nonToolText = extractNonToolText(output);

    if (toolCalls.length === 0) {
      // No tool calls — this is the final response
      finalResponse = nonToolText || output;
      if (finalResponse) onToken?.(finalResponse);
      break;
    }

    // Record the assistant turn so the model sees its own tool request. Native
    // turns carry the structured tool_calls; prompt-based turns carry the text.
    const assistantTurn = nativeCalls.length > 0
      ? { role: 'assistant', content: output || null, tool_calls: streamResult.toolCalls }
      : { role: 'assistant', content: output };

    // Execute each tool call
    for (const call of toolCalls) {
      onToolCall?.({ tool: call.tool, args: call.args, iteration });

      // Approval gate: decided per call, from the actual tool + arguments.
      let result;
      const verdict = toolPolicy ? toolPolicy(call.tool) : 'allow';
      if (verdict === 'approve' && typeof requestApproval === 'function') {
        const approved = await requestApproval({
          tool: call.tool,
          args: call.args,
          description: describeToolCall(call.tool, call.args),
        });
        result = approved
          ? await executeTool(call.tool, call.args)
          : { success: false, declined: true, error: 'The user declined this action. Do not retry it; explain what you would have done, or propose a different approach.' };
      } else {
        result = await executeTool(call.tool, call.args);
      }
      toolResults.push({ tool: call.tool, args: call.args, result });
      // Keep the scratchpad's progress record current (skip control tools).
      if (call.tool !== 'respond' && call.tool !== 'ask_user') {
        scratchpad.recordToolResult(call.tool, call.args, result);
      }

      // Remember successful writes so we can verify them before declaring done.
      if ((call.tool === 'write_file' || call.tool === 'create_file') && result.success && call.args?.path) {
        writtenFiles.set(call.args.path, typeof call.args.content === 'string' ? call.args.content : null);
      }

      // If the tool is "respond", we're done — but first auto-verify any file
      // changes exactly once. If a change didn't stick, feed the discrepancy back
      // and let the model fix it instead of falsely reporting success.
      if (call.tool === 'respond' && result.done) {
        if (writtenFiles.size > 0 && !verifiedOnce) {
          verifiedOnce = true;
          const verification = await verifyWrittenFiles(workspaceProvider, writtenFiles);
          if (!verification.passed) {
            onToolCall?.({ tool: 'verify_changes', args: { files: [...writtenFiles.keys()] }, iteration });
            toolResults.push({ tool: 'verify_changes', args: {}, result: verification });
            modelMessages.push(assistantTurn);
            modelMessages.push({
              role: 'user',
              content: `Automatic verification found problems before finishing:\n${verification.issues.map(i => `- ${i}`).join('\n')}\n\nFix these, then call respond again. Do not claim success until the files verify.`,
            });
            output = '';
            break; // continue the loop so the model can fix
          }
        }
        finalResponse = result.finalResponse;
        // Signal completion
        onToken?.(finalResponse);
        return {
          response: finalResponse,
          toolCalls: toolResults,
          iterations: iteration,
          success: true,
          verified: writtenFiles.size > 0 ? true : undefined,
        };
      }

      // If the tool is "ask_user", return early with the question
      if (call.tool === 'ask_user' && result.awaitingUserInput) {
        return {
          response: result.question,
          toolCalls: toolResults,
          iterations: iteration,
          success: true,
          awaitingUserInput: true,
        };
      }

      // Feed tool result back to the model — as a native tool message when we
      // have a tool_call_id, otherwise as a plain user message.
      modelMessages.push(assistantTurn);
      if (call.id) {
        modelMessages.push({
          role: 'tool',
          tool_call_id: call.id,
          content: JSON.stringify(result).slice(0, 6000),
        });
      } else {
        modelMessages.push({
          role: 'user',
          content: `Tool "${call.tool}" result:\n\`\`\`json\n${JSON.stringify(result, null, 2).slice(0, 6000)}\n\`\`\`\n\nContinue with your next action. If you're done, use the respond tool.`,
        });
      }

      // Reset output for next iteration
      output = '';
      break; // Process one tool call at a time for clarity
    }
  }

  // If we hit max iterations without a respond call
  if (!finalResponse && iteration >= MAX_ITERATIONS) {
    finalResponse = 'I ran out of steps. Here\'s what I accomplished so far — let me know if you want me to continue.';
  }

  return {
    response: finalResponse || '(no response)',
    toolCalls: toolResults,
    iterations: iteration,
    success: true,
  };
}

// Create any missing parent folders for a file/folder path so writes like
// "project/index.html" don't fail because "project" doesn't exist yet. Folder
// creation is best-effort — a failure here surfaces on the actual write.
async function ensureParentFolders(workspaceProvider, path) {
  if (!workspaceProvider?.createFolder || typeof path !== 'string') return;
  const parts = path.split('/').filter(Boolean);
  if (parts.length <= 1) return; // no parent directory
  let prefix = '';
  for (let i = 0; i < parts.length - 1; i++) {
    prefix = prefix ? `${prefix}/${parts[i]}` : parts[i];
    try { await workspaceProvider.inspect(prefix); } // already exists
    catch { try { await workspaceProvider.createFolder(prefix); } catch { /* best-effort */ } }
  }
}

// Read back each written file and confirm it exists (and matches expected
// content when we have it). Returns { passed, issues[] }. Never throws.
async function verifyWrittenFiles(workspaceProvider, writtenFiles) {
  const issues = [];
  if (!workspaceProvider?.readText) {
    return { passed: true, issues: [], note: 'No workspace reader available; skipped verification.' };
  }
  for (const [path, expected] of writtenFiles.entries()) {
    try {
      const actual = await workspaceProvider.readText(path);
      if (typeof actual !== 'string') {
        issues.push(`${path}: could not read the file back after writing.`);
      } else if (typeof expected === 'string' && actual !== expected) {
        issues.push(`${path}: content on disk does not match what was written.`);
      }
    } catch (error) {
      issues.push(`${path}: verification read failed (${error.message}).`);
    }
  }
  return { passed: issues.length === 0, issues };
}

// Helper: flatten a file tree into a list of paths
function flattenTree(items, prefix = '') {
  const result = [];
  for (const item of items) {
    const path = prefix ? `${prefix}/${item.name}` : item.name;
    if (item.type === 'file') {
      result.push(path);
    } else if (item.children) {
      result.push(...flattenTree(item.children, path));
    }
  }
  return result;
}

// Helper: simple glob matching
function matchGlob(path, pattern) {
  const regex = pattern
    .replace(/\./g, '\\.')
    .replace(/\*\*/g, '<<<GLOBSTAR>>>')
    .replace(/\*/g, '[^/]*')
    .replace(/<<<GLOBSTAR>>>/g, '.*');
  return new RegExp(`^${regex}$`).test(path);
}
