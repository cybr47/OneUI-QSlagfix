# Changelog

## v2-glass-fade — no-bounce fade-only experiment

- Keep full-screen native Samsung page slide and the tested GlassUI-style crossfade.
- Eliminate FOLLOW, SETTLE and TAIL spring models, elastic squash/stretch, scale animations and delayed inertia.
- Remove extra VSYNC/Choreographer loop; apply visual transforms directly from Samsung's horizontal slide fraction.
- Do not change gesture recognition, notification interactions, state completion, area-increase vertical mode or Together Quick QS toggle.
- Add bounded fade unit tests and APK checks preventing the old spring engine from being bundled.
- Supplied old `v2-glass-native` trace shows 124/1056 partial/full-jank shade frames (11.7%), median frame span 12.23ms. No performance improvement is claimed until on-device retesting.

## v2-glass-native — spring experiment (unchanged in prior branch)

- GlassUI-derived spring follow/settle/tail on native Samsung pages with new frame callback.

## v2 — clean area-increase base

- Header-only vertical gesture, settings, Together Quick QS row option and untouched Samsung horizontal baseline.
