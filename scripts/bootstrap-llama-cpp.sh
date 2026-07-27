#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REV="0e4a0362239713ea95a6864a17a8de4b0ad90d62"
DIR="$ROOT/third_party/llama.cpp"
if [[ ! -d "$DIR/.git" ]]; then
  mkdir -p "$ROOT/third_party"
  git clone https://github.com/ggerganov/llama.cpp.git "$DIR"
fi
git -C "$DIR" fetch --depth 1 origin "$REV"
git -C "$DIR" checkout --detach "$REV"
test -f "$DIR/include/llama.h" || { echo 'llama.cpp header missing' >&2; exit 1; }
echo "llama.cpp pinned at $(git -C "$DIR" rev-parse HEAD)"
