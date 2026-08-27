/**
 * Tool Policy — one place that decides what the agent may do without asking.
 *
 * Replaces the old pre-flight regex gates (isActionableToolRequest,
 * isAutonomousToolRequest, isGitRequestWithoutRepo, …) which tried to guess a
 * user's intent from their wording BEFORE the model ever ran. Guessing is
 * unnecessary: by the time a tool is about to execute we know exactly which
 * tool it is and with what arguments, so the decision is precise and cheap.
 *
 * Three outcomes per tool call:
 *   'allow'   — run it now (reads, searches, and anything the user pre-approved)
 *   'approve' — pause and show the user an approval card with the exact action
 *   (there is deliberately no 'deny': the user can always approve in place,
 *    so the agent never dead-ends with "go change a setting and retry")
 */

import { isUnattended } from './executionMode.js';

// Tools that only observe. Always safe to run unattended.
export const READ_ONLY_TOOLS = Object.freeze([
  'read_file', 'read_symbol', 'list_files', 'search_code',
  'search_web', 'fetch_page', 'git_status', 'git_diff', 'git_log',
  'ask_user', 'respond',
]);

// Tools that change the workspace, the device, or a remote.
export const MUTATING_TOOLS = Object.freeze([
  'write_file', 'create_file', 'create_folder', 'delete_file',
  'run_terminal', 'git_clone', 'git_commit', 'git_push',
]);

const READ_ONLY = new Set(READ_ONLY_TOOLS);

export function isMutatingTool(tool) {
  return !READ_ONLY.has(tool);
}

/** Is unattended execution currently enabled? */
export function unattendedExecutionEnabled() {
  try { return isUnattended(); }
  catch { return false; }
}

/**
 * Build the decision function handed to the agentic loop.
 * @param {Object} [options]
 * @param {boolean} [options.unattended] override the settings lookup (tests)
 */
export function createToolPolicy({ unattended } = {}) {
  const allowUnattended = typeof unattended === 'boolean' ? unattended : unattendedExecutionEnabled();
  return function decide(tool) {
    if (!isMutatingTool(tool)) return 'allow';
    return allowUnattended ? 'allow' : 'approve';
  };
}

/**
 * Human-readable summary of a pending tool call, for the approval card.
 */
export function describeToolCall(tool, args = {}) {
  switch (tool) {
    case 'write_file': return `Overwrite ${args.path} (${String(args.content || '').split('\n').length} lines).`;
    case 'create_file': return `Create ${args.path}.`;
    case 'create_folder': return `Create the folder ${args.path}.`;
    case 'delete_file': return `Delete ${args.path}. This cannot be undone from the model side.`;
    case 'run_terminal': return `Run in the app sandbox: ${args.command}`;
    case 'git_clone': return `Clone ${args.url} into app-private storage.`;
    case 'git_commit': return `Commit staged changes: "${args.message}".`;
    case 'git_push': return `Push commits to the remote${args.force ? ' (force)' : ''}.`;
    default: return `Run ${tool}.`;
  }
}
