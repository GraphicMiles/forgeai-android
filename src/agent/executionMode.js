/**
 * Execution mode — how much Luna is allowed to do without asking.
 *
 * Replaces the old two-axis system (autonomy levels × automation tiers) that
 * could contradict itself. One setting, two values:
 *
 *   'ask'  — every mutating tool call (write, delete, terminal, git) shows an
 *            approval card with the exact action. Reads never interrupt.
 *   'auto' — mutating calls run unattended. Reads always did.
 */

const STORAGE_KEY = 'luna_execution_mode';

export const EXECUTION_MODES = Object.freeze({
  ASK: 'ask',
  AUTO: 'auto',
});

export function readExecutionMode() {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value === EXECUTION_MODES.AUTO ? EXECUTION_MODES.AUTO : EXECUTION_MODES.ASK;
  } catch {
    return EXECUTION_MODES.ASK;
  }
}

export function writeExecutionMode(mode) {
  const next = mode === EXECUTION_MODES.AUTO ? EXECUTION_MODES.AUTO : EXECUTION_MODES.ASK;
  try { localStorage.setItem(STORAGE_KEY, next); }
  catch { /* storage is best-effort */ }
  return next;
}

export function isUnattended() {
  return readExecutionMode() === EXECUTION_MODES.AUTO;
}
