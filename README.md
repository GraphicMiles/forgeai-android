# Luna

A local utility native agent for Android. Dart draws the interface, Java owns
the device, and llama.cpp does the thinking on-device unless you point Luna
somewhere else.

Luna is not a chat window with tools bolted on. The device is her workplace:
one folder you grant her, one model you choose, and a stop before anything
permanent.

## What she can do

Nine tools, all scoped to the folder you grant:

| Free | Held until you approve |
| --- | --- |
| List files, read a file, search | Write, create a file, create a folder, delete, rename |

No terminal, no git, no web. Those were removed on purpose — a tool that can do
anything cannot be reasoned about.

Files that hold secrets stay locked in both modes: `.env`, `.netrc`, key
material, keystores, `.ssh`, `.gnupg`, `.aws`. Luna cannot open them at all,
even in unattended mode. She will not read a file over 2 MiB.

Every change she makes is backed up first, so a wrong edit is one tap of Undo.

## Where the model runs

- **On device** — eight GGUF models from 93 MB to 1.7 GB, pinned to immutable
  Hugging Face revisions and checked by SHA-256. Downloads resume. Models the
  device does not have RAM for are shown greyed with the reason.
- **Your computer** — an Ollama address on the same network.
- **Cloud** — any OpenAI-compatible endpoint and key. Keys go in the Android
  keystore, never in a file.

On-device models work with the radio off. A cloud model sends your prompt and
whatever file contents it reads to that provider; the app says so where you
turn it on.

## Screens

Four, and no more: **Chat**, **Files**, **Models**, **Settings**.

One 27px title per screen and exactly one filled-black surface: the approval
card in Chat, the running model in Models, the held-tool chips in Settings.
State reads through fill and weight, never colour. The mascot is the only thing
in the app allowed to be purple.

## Layout

```
lib/                        Dart — interface only
  theme.dart                the design system, ported from docs/design/luna-screens.html
  core/luna_core.dart       one method channel, one event stream, mirrored state
  widgets/common.dart       mascot, rows, groups, sheets, pills, chips
  screens/                  chat, files, models, settings
android/app/src/main/
  java/ai/luna/app/         Java — everything that touches the device
    AgentEngine.java        the loop: prompt, parse, approve, act, repeat (max 6)
    OnDeviceRuntime.java    JNI to llama.cpp
    ModelStore.java         catalogue, resumable downloads, SHA-256 gate
    WorkspaceStore.java     SAF: list, read, write, backups, undo, deny-list
    CloudProvider.java      OpenAI-compatible chat, Ollama discovery
    CredentialVault.java    AES/GCM in the AndroidKeystore
    LunaBridge.java         the seam
  cpp/                      llama.cpp binding
```

## Building

CI does it: `.github/workflows/android-apk.yml` fetches the pinned llama.cpp,
builds a release arm64 APK and fails the run if the **unpacked** APK exceeds
50 MiB.

Locally:

```
bash scripts/bootstrap-llama-cpp.sh
flutter pub get
flutter build apk --release --target-platform android-arm64 --tree-shake-icons
```

## Known limits

- One conversation. "New chat" clears it; there is no history list.
- The release build is signed with the debug key so CI can hand you an
  installable artifact. Swap in a real keystore before shipping.
- arm64-v8a only.
