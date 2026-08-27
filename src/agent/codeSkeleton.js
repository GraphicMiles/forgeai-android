/**
 * Code Skeleton Extraction
 *
 * Token saver + smarter context: instead of dumping a whole 500-line file into
 * the model's context, give it the file's OUTLINE — imports, exports, and
 * top-level declaration signatures with their line numbers. The agent can then
 * request the full body of only the specific symbol it needs.
 *
 * Pure, dependency-free, works offline. Language-agnostic-ish (tuned for JS/TS
 * but degrades gracefully for others).
 */

// Escape a string for safe use inside a RegExp (user/model-supplied symbols).
function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

const SIGNATURE_PATTERNS = [
  // export function foo(...) / async function
  /^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s*\*?\s*([A-Za-z0-9_$]+)\s*\([^)]*\)/,
  // export const foo = (...) => / arrow fns
  /^\s*(?:export\s+)?(?:default\s+)?(?:const|let|var)\s+([A-Za-z0-9_$]+)\s*=\s*(?:async\s*)?\(?[^=]*\)?\s*=>/,
  // export class Foo / class Foo extends Bar
  /^\s*(?:export\s+)?(?:default\s+)?class\s+([A-Za-z0-9_$]+)/,
  // TS interface / type / enum
  /^\s*(?:export\s+)?(?:interface|type|enum)\s+([A-Za-z0-9_$]+)/,
  // class methods (indented) foo(...) {  — best effort
  /^\s{2,}(?:public\s+|private\s+|protected\s+|static\s+|async\s+|get\s+|set\s+)*([A-Za-z0-9_$]+)\s*\([^)]*\)\s*\{/,
];

const IMPORT_PATTERN = /^\s*(?:import\b.*|(?:const|let|var)\s+.*=\s*require\([^)]*\))/;

/**
 * Extract a compact skeleton from source code.
 * @returns {{ skeleton:string, lines:number, symbols:string[], truncated:boolean }}
 */
export function extractSkeleton(content, { maxLines = 60 } = {}) {
  const text = String(content ?? '');
  const lines = text.split('\n');
  const imports = [];
  const decls = []; // { line, name, text }
  const symbols = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (IMPORT_PATTERN.test(line)) {
      imports.push(line.trim());
      continue;
    }
    for (const pat of SIGNATURE_PATTERNS) {
      const m = line.match(pat);
      if (m) {
        // Keep the signature line (up to the opening brace/arrow), trimmed.
        const sig = line.replace(/\s*\{?\s*$/, '').trim();
        decls.push({ line: i + 1, name: m[1], text: sig });
        if (!symbols.includes(m[1])) symbols.push(m[1]);
        break;
      }
    }
  }

  const out = [];
  if (imports.length) {
    out.push('// imports');
    out.push(...imports.slice(0, 20));
    if (imports.length > 20) out.push(`// ...(+${imports.length - 20} more imports)`);
    out.push('');
  }
  if (decls.length) {
    out.push('// declarations (line: signature)');
    for (const d of decls) out.push(`${d.line}: ${d.text}`);
  } else if (!imports.length) {
    // Nothing structural recognized — return a head slice so it's still useful.
    out.push(...lines.slice(0, 30));
  }

  let skeleton = out.join('\n');
  let truncated = false;
  const skelLines = skeleton.split('\n');
  if (skelLines.length > maxLines) {
    skeleton = skelLines.slice(0, maxLines).join('\n') + `\n// ...(skeleton truncated, ${skelLines.length - maxLines} more lines)`;
    truncated = true;
  }

  return { skeleton, lines: lines.length, symbols, truncated };
}

/**
 * Decide whether skeleton-first reading is worth it. Small files are cheaper to
 * send whole; big files benefit from an outline first.
 */
export function shouldUseSkeleton(content, { minLines = 80 } = {}) {
  return String(content ?? '').split('\n').length >= minLines;
}

/**
 * Extract the body of a single named symbol (best-effort brace matching), so the
 * agent can fetch just the function it cares about after seeing the skeleton.
 * @returns {string|null}
 */
export function extractSymbolBody(content, name) {
  const text = String(content ?? '');
  if (!name) return null;
  const lines = text.split('\n');
  const nameRe = new RegExp(`\\b${escapeRegExp(name)}\\b`);
  let startLine = -1;
  for (let i = 0; i < lines.length; i++) {
    if (SIGNATURE_PATTERNS.some(p => p.test(lines[i])) && nameRe.test(lines[i])) { startLine = i; break; }
  }
  if (startLine === -1) return null;

  // Walk forward tracking brace depth from the first '{' at/after startLine.
  let depth = 0;
  let started = false;
  const collected = [];
  for (let i = startLine; i < lines.length; i++) {
    const line = lines[i];
    collected.push(line);
    for (const ch of line) {
      if (ch === '{') { depth++; started = true; }
      else if (ch === '}') { depth--; }
    }
    if (started && depth <= 0) break;
    if (collected.length > 400) break; // safety
  }
  return collected.join('\n');
}
