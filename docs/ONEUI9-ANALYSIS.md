# One UI 9 APK findings

Inspected 2026-10-31. Supplied file: `SystemUI(1).apk` / `SystemUI.apk`.
Package `com.android.systemui`, version name `17`, version code `37`.
SHA-256: `81a9e975a9a88a92a94f855c1c4a69dc68dcff28b5e28adfac5adc8ebebc68b4`.

All five DEX files were enumerated with Androguard 4.1.4. Relevant methods were disassembled and decompiled with DAD. Where DAD produces ambiguous control flow/types, DEX instructions are authoritative. The proprietary APK/decompiled Samsung source is not redistributed.

## v2 scope: gesture handling only

The original OneUI-QSlagfix main branch modified media waveform sampling (`stepX`) in
Samsung's visualizer. **ShadeSwitch v2 does not install any of those sampling
hooks.** Its only optional SystemUI hooks are the header-only vertical panel
gesture and the Together-style compact Quick QS row. With both toggles disabled,
there are no SystemUI method hooks installed.

The previous renderer investigation remains in the repository's historical
branches. There is no evidence that coarsening visualizer sampling is necessary
to improve native horizontal panel transitions.

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

## Native drag and release used by area-increase 2.3.0

The same supplied APK was rechecked for the complete gesture path. `PanelSlideEventHandler.initiateSlide(MotionEvent)` initializes the real coordinates, pointer, tracker and `stateOnDown`, then derives `isInChangeSpotOnDown` from the native header height and the left/right divider. `SecPanelSplitHelper.onIntercept` independently checks the header height, and calls `slidePointerDeltas(MotionEvent)` before `updateDirection(float,float)` and its vertical eligibility check.

The module widens the start flag and, only after native deltas have been calculated, temporarily adapts `initialY` for the two interception height checks. The real origin is restored on exit, including exceptions. `SecPanelSplitHelper.handleTouch` remains native: MOVE calls `slide` with the real finger delta; UP/CANCEL uses the native release/cancellation path and `createSlideAnimatorAndRun` with the real velocity and dragged distance. No module-owned animation or forced completion remains. See [current gesture behavior](NO-SWIPE-PANEL-SWITCH.md).

The native interception caller is explicitly deoptimized to preserve its hooked callee calls against ART inlining, using the documented [libxposed API](https://github.com/libxposed/api). Live touch/render/release methods are not explicitly deoptimized.

## Lockscreen shade used by area-increase 2.3.1

In classes3.dex, StatusBarState.toString maps 0 to SHADE, 1 to KEYGUARD and 2 to SHADE_LOCKED. SecQSImplAnimatorBase.onStateChanged and the concrete QS animator callbacks assign that value to QsAnimatorState.state. SecPanelSplitHelper.onIntercept rejects state 1, while slide contains explicit status-2 branches for native vertical switching from the locked shade, including qs_swipe_ver_keyguard analytics.

The module previously required status 0. The area-increase adapter (also reused by v2) accepts 0 or 2 while keeping native interception and all other availability checks. No media-wave sampling hooks are installed in v2.

## Framework API

Uses `io.github.libxposed:api:102.0.0`, the latest release verified during this work. Modern no-argument XposedModule entry, onModuleLoaded/onPackageReady, interceptor chains, compileOnly dependency, META-INF metadata/scope, protective exception mode and R8 rules follow:
- https://github.com/libxposed/api
- https://libxposed.github.io/api/io/github/libxposed/api/package-summary.html
- https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API

The installed LSPosed implementation must support API 102. Hot reload is disabled; restart SystemUI/reboot after changing branches.

## Limits

Static contract and APK/build checks do not establish device performance, battery savings, appearance or future-firmware compatibility. There is no connected S25 here. Device checks are documented separately for the gesture branch.
