# Changelog

## v2-glass-native — experimental true frame-driven transplant

- Independently reconstructed GlassUI 2.3.4's three spring models, 1/240-s substep integration, follow/settle switching and trailing edge.
- Intercept Samsung's `PanelTransitionAnimator.slide` for active horizontal transitions; **do not execute stock view movement** while the separate animation is active.
- Drive native Quick Settings and Notifications views on a single Choreographer callback rather than changing a release PathInterpolator.
- Port the GlassUI custom sheet's translation/alpha/stretch behavior to the native QS view; optionally cache it as a hardware layer only while moving.
- Preserve `area-increase` vertical gestures, Together compact tile option, and the clean `v2` branch.
- Add pure Java follow/reversal/settling regressions; runtime visual testing is required.

## v2 — clean area-increase restart

- Settings activity and Together Quick QS row option.
- Original header-only vertical swipe, native horizontal animation in default mode.
- Removed old QS lagfix media-wave hooks.
