# Changelog

## 2.0.0-oneui9 — main — 2026-10-31
- Verify the legacy wave constructor/stepX patch against the supplied One UI 9 Indian-ROM SystemUI 17 APK; retain its sampling optimization.
- Cover the additional colourful-wave renderer with effective stepX=8 (draw clamps to 1..8) and preserve the fix across mutable style updates.
- Migrate legacy Xposed 82 to libxposed API 102.0.0, modern lifecycle/entry files, fixed SystemUI scope and protective hooks.
- Add a checksum-pinned Gradle wrapper and workflow producing app-debug.apk and optimized app-release.apk, APK content checks, certificate details and SHA-256 hashes.
- Release is non-debuggable, minified, resource shrunk and excludes module diagnostics. Repository signing secrets are supported; absent keys use an explicitly documented test certificate.
