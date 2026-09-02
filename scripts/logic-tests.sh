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

# Git lives in the app as a library now (AppGitStore), so the same JGit that
# the APK ships has to be on the test classpath too, along with its deps.
jgit="$work/jgit.jar"
ewah="$work/ewah.jar"
slf4j="$work/slf4j-api.jar"
codec="$work/commons-codec.jar"
curl -sL -o "$jgit" \
  "https://repo1.maven.org/maven2/org/eclipse/jgit/org.eclipse.jgit/6.10.0.202406032230-r/org.eclipse.jgit-6.10.0.202406032230-r.jar"
curl -sL -o "$ewah" \
  "https://repo1.maven.org/maven2/com/googlecode/javaewah/JavaEWAH/1.2.3/JavaEWAH-1.2.3.jar"
curl -sL -o "$slf4j" \
  "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar"
curl -sL -o "$codec" \
  "https://repo1.maven.org/maven2/commons-codec/commons-codec/1.16.1/commons-codec-1.16.1.jar"
deps="$json:$jgit:$ewah:$slf4j:$codec"

# The platform jar's org.json throws "Stub!", so the real one goes first.
javac --release 11 -encoding UTF-8 -nowarn \
  -cp "$deps:$platform:$root/tools/jvm-stubs" \
  -d "$work/classes" \
  "$root"/android/app/src/main/java/ai/luna/contracts/*.java \
  "$root"/android/app/src/main/java/ai/luna/builtin/*.java \
  "$root"/android/app/src/main/java/ai/luna/runtime/*.java \
  "$root"/android/app/src/main/java/ai/luna/app/*.java \
  "$root"/tools/tests/ai/luna/app/*.java

java -cp "$deps:$platform:$work/classes" ai.luna.app.WorkspacePathTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.TextEditTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.RecoveryTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.RunGuardsTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.GitTreeTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.MemoryRecoveryTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.ProviderConfigTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.ContractsTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.RegistryTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.SkillsTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.AgentsTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.PluginsTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.WorkflowTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.MemoryTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.RouterTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.EnvironmentTest
java -cp "$deps:$platform:$work/classes" ai.luna.app.SubAgentTest
java -Dluna.root="$root" -cp "$deps:$platform:$work/classes" ai.luna.app.ExamplesTest
