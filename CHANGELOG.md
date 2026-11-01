# Changelog

## 2.2.0-animated-switch — no-swipe-panel-switch — 2026-11-01
- Panel switches are animated again: the leaving panel fades out towards the bottom and the arriving panel fades in from the top. The switch now runs Samsung's own vertical slide (`PanelSlideEventHandler.createSlideAnimatorAndRun`, direction DOWN) instead of calling `slide$1(int)`, which committed the state and rendered only the final frame. Verified against the supplied One UI 9 SystemUI dex files.
- Release builds are no longer minified. The release APK did not apply the gesture while the debug APK did, and R8 was the only build step that changed the executed code. The R8 effect was not isolated; release now takes the same D8 path as debug.
- Both APKs log a few status lines per SystemUI start (loaded, hooks installed) and WARN lines on failure, so release builds can be diagnosed from the LSPosed log. `verify-apks.py` now requires those lines instead of forbidding them.
- A single switch refused by Samsung is ignored and logged once; it no longer disables the gesture until SystemUI restarts. Only genuine contract/reflection failures do that.
- Touching the header during a running panel animation no longer snaps it to its end: the touch-down check is read-only. A new swipe during an animation still settles the old one first.
- Skip the reflective helper lookup for touch events of streams the module did not buffer.
- Contract file records the new members (`createSlideAnimatorAndRun`, `updateResource`, `maxDragWidth`, `flingAnimationUtils`, `FlingAnimationUtils.mMinVelocityPxPerSecond`, `Direction.DOWN`) and which fields are static. New `scripts/verify-dex-contracts.py` checks them against raw `classes*.dex` files with only the Python standard library.
- Unit tests updated for the animated path (in-flight animation counts as accepted, refusal is reported and recoverable, rapid swipes during an animation stay consistent, `canToggle` is read-only).

## 2.1.1-diagnostic — no-swipe-panel-switch — 2026-10-31
- Investigate unchanged panel behavior with module/framework lifecycle and hook-registration diagnostics.
- Log the first eight panel DOWN events and first native interception/slide only, in the debug APK, to expose rejected mode/root/header conditions.
- Use INFO for debug diagnostics so ordinary framework logs can capture them; verify all diagnostic strings are removed from release.
- No gesture behavior change is claimed without on-device logs.

## 2.1.0-no-swipe — no-swipe-panel-switch — 2026-10-31
- Keep closed-panel left/right/reversed routing; disable horizontal panel switches while open.
- Reuse the existing native header swipe area across its full width for unlimited Notifications/QS downward toggles; preserve upward close handling.
- Apply Samsung terminal states immediately, cancel previous panel animation on the UI thread and consume each committed gesture tail to prevent double toggles or collapse.
- Add classifier/state regression tests, checked APK contracts and an explicit on-device validation/assumptions list.

## 2.0.0-oneui9 — main — 2026-10-31
- Verify the legacy wave constructor/stepX patch against the supplied One UI 9 Indian-ROM SystemUI 17 APK; retain its sampling optimization.
- Cover the additional colourful-wave renderer with effective stepX=8 (draw clamps to 1..8) and preserve the fix across mutable style updates.
- Migrate legacy Xposed 82 to libxposed API 102.0.0, modern lifecycle/entry files, fixed SystemUI scope and protective hooks.
- Add a checksum-pinned Gradle wrapper and workflow producing app-debug.apk and optimized app-release.apk, APK content checks, certificate details and SHA-256 hashes.
- Release is non-debuggable, minified, resource shrunk and excludes module diagnostics. Repository signing secrets are supported; absent keys use an explicitly documented test certificate.
- Use current Node 24 Actions and explicitly install platform-tools; the retired tools SDK package broke the original setup action on GitHub runners.
