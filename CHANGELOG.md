# Changelog

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
