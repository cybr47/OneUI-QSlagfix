# ShadeSwitch — GlassUI native-transition transplant (experimental)

This branch starts from the clean `v2` baseline and **replaces Samsung's visual horizontal panel slide**, instead of merely changing the native release interpolator.

## What is actually implemented

When **Vertical swipe to switch panels is OFF**:
- `PanelTransitionAnimator.slide(float,float,Direction,int)` supplies only finger/release progress; we skip Samsung's stock per-frame translation and Compose slide-fraction writes while in the separate-style horizontal transition.
- A single `Choreographer.FrameCallback` follows that progress with the **three independently reconstructed GlassUI 2.3.4 physical spring responses**. They were extracted from the user-provided `SideSlide`, `SpringStep`, and `Logic$Spring` bytecode: FOLLOW period 0.15 s / drag 0, SETTLE 0.34 s / drag 0.15, trailing-edge TAIL 0.24 s / drag 0.35. The integrator is capped at 1/240-second steps and 1/30-second frames, as in the supplied APK.
- The animation drives **Samsung's original QS and notification views**, with a full-width page slide, QS opacity `min(1, 1.5*shown)`, and leading-edge squash/stretch (capped at 12% width). The QS view uses a temporary hardware layer, like GlassUI's custom sheet.
- The existing native-panel adapter manages Samsung's Compose visibility, clock/date/header, native state completion, and property restoration. Samsung still handles gestures, notifications, media controls, panel state, release distance, reverse direction, and panel closure. No GlassUI custom tiles, sliders, button animations, widgets or visuals are bundled.

When **Vertical swipe to switch panels is ON**: the original header-only `area-increase` gestures remain; horizontal visual hooks are not installed.

The independent **Together mode → Hide Quick QS row** toggle remains unchanged.

## Provenance and limits

This is a **reverse-engineered, independently implemented adaptation** from the user-supplied GlassUI APK, not an exact binary extraction of GlassUI's closed-source panel host. GlassUI's `SideSlide.Host` is implemented by `Panel$3` and operates on proprietary custom panel views. To work with Samsung stock UI, the native view host must be replaced; the motion constants and integration are faithful, but the rendered view hierarchy is different.

The old `v2-glass-motion` only changed the native release interpolator. **That is not used in this branch.** The new hook applies during *interactive dragging and release*. The stable `v2` branch is unchanged.

A successful CI build proves compilation and tests only; it cannot prove appearance, correctness, smoothness, or reduced jank on a real Galaxy S25. Inspect LSPosed for `GlassUI dual-spring horizontal visual engine installed` and `Glass native horizontal target received` to distinguish hook registration from actual execution. Disable the separate GlassUI module when testing: the two can intercept the same native methods.

## Safety and rollback

All modified view properties and hardware-layer state are restored when Samsung settles/clears the transition. Reflection/install errors fail closed. If any visual regression occurs, use the signed clean `v2` APK and restart SystemUI. Keep this branch experimental until tested; do not merge into `v2` automatically.
