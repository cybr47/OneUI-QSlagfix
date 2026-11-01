# Changelog

## 2.3.1-lockscreen — area-increase — 2026-11-01
- Enable the existing common-header drag/release adapter for SHADE_LOCKED (status 2), as well as SHADE (status 0), so it applies to split panels opened from the lockscreen.
- Retain Samsung's native keyguard, panel-disabled, detail-screen and pop-over policies, plus the tested drag/reversal/release and button behavior.
- The media-wave sampling hooks already apply regardless of lock state; no renderer or animation hooks are added.

## 2.3.0-native-drag — area-increase — 2026-11-01
- Replace the automatic switch trigger with Samsung's original touch interception and drag/release handling. Finger position drives the transition; Samsung decides whether to complete or return on release.
- Widen the native start-area flag across the expanded clock/date/button row. Adapt the separate native header-height checks only during interception, after real pointer deltas have been calculated, and restore the real origin before touch/release handling.
- Remove the module's forced fling velocity, animation launching, transition snapping and DOWN/UP replay. Buttons now receive the original touch stream; native parent interception cancels a button press when a drag takes over.
- Keep horizontal panel switching disabled at native direction classification, while preserving an established DOWN drag when the finger reverses or moves diagonally.
- Deoptimize only the native interception caller so its small hooked callees cannot be bypassed by ART inlining. Rendering, live drag and release animation methods are not explicitly deoptimized.
- Retain the tested expanded area, media sampling, signing and release build settings. Add regression coverage for original-coordinate restoration and horizontal gesture policy; remove tests for the deleted automatic toggle reducer.

## 2.2.1-area-increase — area-increase — 2026-11-01
- Extend the open-panel swipe area through the existing clock/date and QS edit/power/settings row, using Samsung's measured views and configured row height.
- Preserve native button taps through the existing DOWN/UP replay; downward swipes over the buttons switch panels without clicking them.
- Keep the last settled header bounds during panel animations so moving content cannot expand the gesture area into notifications or QS tiles. Clear those bounds on configuration changes and detach.
- Keep the animated switch, horizontal-swipe blocking, upward close, media sampling, signing and release settings from 2.2.0.
- Enable the existing build workflow for this branch; versionCode is 6 for installation over 2.2.0.

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
