# Build and install Luna from an Android phone

No laptop is required if GitHub Actions is enabled for the repository.

1. Open the repository in GitHub from the phone.
2. Open **Actions** and select **Build Android APK**.
3. Tap **Run workflow** and run it on `main`.
4. Wait for the workflow to finish.
5. Open the completed workflow run.
6. Download the `luna-debug-apk` artifact.
7. Extract the ZIP on the phone and tap `app-debug.apk`.
8. Allow the browser or file manager to install unknown apps when Android asks.
9. Install and launch Luna.

The workflow runs the test suites, builds the web app, syncs Capacitor, compiles the Android
native runtime (llama.cpp included), and uploads the APK.

## Building on a machine

```bash
npm ci
npm run android:build
```

This bootstraps llama.cpp, runs the environment preflight, builds the web bundle, syncs
Capacitor, and assembles a debug APK.

Requirements: JDK 21, Android SDK, NDK, and CMake.

## Notes

- The application id is `ai.luna.app`. It was renamed from the previous `ai.forgeai.app`, so a
  build of this branch installs alongside older builds rather than upgrading them, and
  `public/.well-known/assetlinks.json` needs the signing fingerprint re-paired with the new
  package name before app links will verify.
- Native capabilities (SAF workspace, terminal, Git, on-device models, research) exist only in
  the Android build. `npm run dev` gives a browser shell where those tools are unavailable.
