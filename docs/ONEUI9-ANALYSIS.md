# One UI 9 APK findings

Inspected 2026-10-31. Supplied file: `SystemUI(1).apk` / `SystemUI.apk`.
Package `com.android.systemui`, version name `17`, version code `37`.
SHA-256: `81a9e975a9a88a92a94f855c1c4a69dc68dcff28b5e28adfac5adc8ebebc68b4`.

All five DEX files were enumerated with Androguard 4.1.4. Relevant methods were disassembled and decompiled with DAD. Where DAD produces ambiguous control flow/types, DEX instructions are authoritative. The proprietary APK/decompiled Samsung source is not redistributed.

## Existing patch and changes

Starting main: `68fd7d47cc6b466690874bab1057e2eb59815e75`.
It contains exactly one optimization: after constructing the legacy media renderer, set its integer stepX from 2 to 10. There are no existing blur, notification layout, panel animation or general QS patches.

| APK class | Verified contract | Result |
| --- | --- | --- |
| `com.android.systemui.media.audiovisseekbar.renderer.track.auto.MultiWaveAreaTrackRenderer` | `<init>(android.view.View, AudioVisSeekBarConfig)`; `stepX:int` | Present in classes2.dex; original hook still matches. |
| `com.android.systemui.media.colorfulwave.renderer.MultiWaveAreaTrackRenderer$WaveStyle` | `<init>(int,int,int,PorterDuff.Mode,float,float,float,float,float,float,float,int)`; `stepX:int` | The additional wave path stores sampling step per style. |
| `com.android.systemui.media.colorfulwave.renderer.MultiWaveAreaTrackRenderer` | `waveStyles:java.util.List`; `setWaveStyles(java.util.List):void`; `draw(android.graphics.Canvas):void` | draw clamps stepX to 1..8. Style updates copy mutable fields into existing instances. |

Main retains a minimum of 10 on the legacy renderer and uses a minimum of 8 on colourful styles. Values already higher are preserved. The colourful draw path clamps values to 8, so 10 would not give a larger effective step. Hook the concrete style constructor (the Kotlin default constructor delegates to it), and reapply after setWaveStyles. Nothing hooks draw, frame callbacks or a repeating timer.

This extends the upstream sampling strategy; it is not evidence that wave sampling causes all QS stutters. Which renderer is active depends on Samsung's media-player path/settings. No older Samsung APK was supplied for a full version-to-version diff.

## Panel switching contracts for the separate branch

Verified in classes3.dex:
- `SecPanelSplitHelper`: `currentState:int`, `stateOnDown:int`, `stateToChange:int`, `panelExpanded:boolean`, `draggedFraction:float`, `panelSlideEventHandler`; `onIntercept(MotionEvent)`, `handleTouch(MotionEvent)`, `slide(float,Direction,boolean)`, `slide$1(int)`, `updateState(int)`, `setDraggedFraction(float)`, `isReversed()`.
- `PanelTransitionState`: state 0 is QS, 1 is Notifications, 2 is transition, 3 is combined shade.
- `PanelSlideEventHandler` owns the pointer, initial coordinates, scaled touch slop, divider, header lookup, direction and `sliderAnimator:ValueAnimator`.
- `initiateSlide(MotionEvent)` uses `ShadeHeaderController.header.getMeasuredHeight()` as the existing vertical boundary and the divider/reversed state to restrict the second downward pull to the opposite side.
- `SecPanelSplitHelper.isReversed()` combines Samsung's reverse setting and layout direction. Closed-panel routing must remain with Samsung.
- `slide$1(int)` calls updateState and sends a completed DOWN transition to SecQSImplAnimatorManager. updateState notifies listeners, updates SplitStateRepository and visibility. Reuse this; do not substitute a private panel boolean.
- `PanelTransitionAnimator.slide(...)` publishes Compose slide fraction and view transforms. Horizontal gestures otherwise drive repeated updates through the drag/fling animator.

## Date/button row used by area-increase

Rechecked against the same APK checksum above:

- `res/layout/sec_qqs_date_buttons.xml` identifies the native row as `quick_qs_date_buttons`; it contains the clock/date and `header_settings_container`.
- `SecQuickSettingHeaderKt.HeaderContainer` in classes3.dex uses `sec_qs_buttons_container_height` for its Compose Box. The clock/date Row is aligned `CenterStart`; the clock AndroidView has 1dp top padding, and the date is bottom-aligned with `SecQSPanelResourceNormalPicker.getQSClockBottomPadding`, which reads `date_text_bottom_padding`.
- The AndroidView factories set the tags `compose_header_clock` and `compose_header_date` and inflate `sec_qs_panel_clock` / `sec_qs_panel_date` respectively. They provide native view positions even though the surrounding QS row and its buttons use Compose.

The area-increase hit-test reads those live views/resources only at touch-down. It does not hook Compose rendering or change the button click handlers. During a transition it uses the last settled hit-test boundary, rather than following the animated date row down the screen.

## Framework API

Uses `io.github.libxposed:api:102.0.0`, the latest release verified during this work. Modern no-argument XposedModule entry, onModuleLoaded/onPackageReady, interceptor chains, compileOnly dependency, META-INF metadata/scope, protective exception mode and R8 rules follow:
- https://github.com/libxposed/api
- https://libxposed.github.io/api/io/github/libxposed/api/package-summary.html
- https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API

The installed LSPosed implementation must support API 102. Hot reload is disabled; restart SystemUI/reboot after changing branches.

## Limits

Static contract and APK/build checks do not establish device performance, battery savings, appearance or future-firmware compatibility. There is no connected S25 here. Device checks are documented separately for the gesture branch.
