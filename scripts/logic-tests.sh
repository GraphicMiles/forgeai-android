#!/usr/bin/env bash
# Runs the engine's decision logic on a plain JVM: what Luna remembers, and
# what happens to a job that is stopped or killed halfway through.
#
# No emulator and no Gradle. The Java core is compiled against the platform
# jar plus the small hand-written stubs in tools/jvm-stubs, and the pure parts
# are then executed for real.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
work="${TMPDIR:-/tmp}/luna-logic-tests"
rm -rf "$work"
mkdir -p "$work/classes"

platform=""
for candidate in "${ANDROID_HOME:-}/platforms/android-35/android.jar" \
                 "${ANDROID_HOME:-}/platforms/android-34/android.jar" \
                 "${ANDROID_SDK_ROOT:-}/platforms/android-35/android.jar" \
                 "${ANDROID_SDK_ROOT:-}/platforms/android-34/android.jar"; do
  if [ -f "$candidate" ]; then platform="$candidate"; break; fi
done
if [ -z "$platform" ]; then
  echo "No android.jar found. Set ANDROID_HOME." >&2
  exit 1
fi

json="$work/json.jar"
curl -sL -o "$json" \
  "https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar"

# The platform jar's org.json throws "Stub!", so the real one goes first.
javac --release 11 -encoding UTF-8 -nowarn \
  -cp "$json:$platform:$root/tools/jvm-stubs" \
  -d "$work/classes" \
  "$root"/android/app/src/main/java/ai/luna/app/*.java \
  "$root"/tools/tests/ai/luna/app/*.java

java -cp "$json:$platform:$work/classes" ai.luna.app.MemoryRecoveryTest
