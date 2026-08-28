#!/usr/bin/env bash
# Signs plugin sources into .lunapkg.json packages the app will accept.
#
#   scripts/luna-package.sh                       # every example, into assets
#   scripts/luna-package.sh path/to/plugin.json   # one, next to its source
#   scripts/luna-package.sh --verify file.lunapkg.json
#
# The packager is the app's own code (PluginManifest + PluginVerifier), so what
# gets hashed here is by construction what gets checked on the device.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
work="${TMPDIR:-/tmp}/luna-package"
mkdir -p "$work/classes"

json="$work/json.jar"
if [ ! -f "$json" ]; then
  curl -sL -o "$json" \
    "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"
fi

javac --release 11 -encoding UTF-8 -nowarn \
  -cp "$json" -d "$work/classes" \
  "$root"/android/app/src/main/java/ai/luna/contracts/PluginManifest.java \
  "$root"/android/app/src/main/java/ai/luna/contracts/Capability.java \
  "$root"/android/app/src/main/java/ai/luna/runtime/PluginVerifier.java \
  "$root"/tools/package/PluginPackager.java

run() { java -cp "$json:$work/classes" ai.luna.tools.PluginPackager "$@"; }

if [ "${1:-}" = "--verify" ]; then
  shift
  for file in "$@"; do run verify "$file" --strict; done
  exit 0
fi

key="${LUNA_SIGNING_KEY:-$work/signing-key.b64}"

if [ $# -gt 0 ]; then
  for source in "$@"; do
    run sign "$source" "${source%.json}.lunapkg.json" --key "$key"
  done
  exit 0
fi

mkdir -p "$root/assets/plugins"
for source in "$root"/examples/plugins/*.json; do
  case "$source" in *.lunapkg.json) continue;; esac
  name="$(basename "$source" .json)"
  run sign "$source" "$root/assets/plugins/$name.lunapkg.json" --key "$key"
done
