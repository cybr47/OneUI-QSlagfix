# One UI QS Lag Fix

SystemUI-only libxposed module targeting the supplied Samsung One UI 9 Indian-ROM SystemUI 17 APK. See [APK findings](docs/ONEUI9-ANALYSIS.md) and [changelog](CHANGELOG.md).

## Branches
- **main**: media-wave sampling only; Samsung's panel gestures.
- **no-swipe-panel-switch**: separate gesture variant: horizontal panel switching disabled; repeated downward header swipes toggle panels. See [gesture behavior, assumptions and rollback](docs/NO-SWIPE-PANEL-SWITCH.md).

The original optimization reduces the legacy media progress wave's sampling resolution (stepX=10). One UI 9 also contains a colourful renderer whose per-style step is clamped to 1..8; this fork uses 8 and reapplies after style updates. This reduces sampling work. Smoothness and battery improvements have not been measured on-device.

## Install and runtime rollback
1. Use an LSPosed implementation supporting **modern libxposed API 102**.
2. Install one branch APK, enable it with **System UI / com.android.systemui** scope and reboot.
3. Both branches share one application ID; install only one variant.

Disable this module in LSPosed and reboot to restore stock behavior. To keep media sampling and discard the gesture experiment, install the main APK (same certificate; its older versionCode may require uninstalling the branch APK first), enable it and reboot. If SystemUI is unusable, follow your LSPosed implementation's documented module-disable/recovery procedure.

## Build
JDK 21, SDK platform 36 and build-tools 36.0.0:
```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease
ANDROID_HOME=/path/to/android-sdk bash scripts/package-apks.sh
```

The **Build APKs** Actions workflow runs on pushes to both branches and supports manual dispatch. Artifacts contain:
- `app-debug.apk`: debuggable, module diagnostics enabled.
- `app-release.apk`: R8 optimized, resource shrunk, non-debuggable, module diagnostics removed.
- `BUILD.txt`, signing certificate details and `SHA256SUMS.txt`.

Both APK variants are signed with the same repository keystore on push/manual builds. Repository secrets `KEYSTORE_BASE64` and `KEYSTORE_PASSWORD` are required; `KEYSTORE_ALIAS` and `KEY_PASSWORD` are optional (key password defaults to store password). Missing credentials fail the build before compilation; there is no test-key fallback. Certificate verification confirms both APK signatures match and records the public SHA-256 fingerprint in `SIGNING.txt`. The private keystore is temporary and is never committed or uploaded. PR builds run checks without accessing signing secrets or publishing signed APKs. An older test-signed installation must be uninstalled once before installing an APK signed with the repository key.

## Source rollback
Pre-work main: `68fd7d47cc6b466690874bab1057e2eb59815e75`.
Use `git revert <commit>` then a normal push for a history-preserving rollback. Stage 2 is confined to its branch and can be discarded without modifying main.

## Attribution and license
The original stepX optimization is from Samsung QS Lag Fix / OneUI-QSlagfix, originally targeting UN1CA 3. See [NOTICE](NOTICE) and [LICENSE](LICENSE) (Apache License 2.0).
