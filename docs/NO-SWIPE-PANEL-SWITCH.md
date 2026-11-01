# Status-bar swipe toggle branch

## area-increase variant (2.2.1)

This branch starts from `no-swipe-panel-switch` commit `5923eb6761ead9c5f291d895ccf04f3a569bba56`. The switch animation, direction classifier, touch threshold and native tap replay are retained.

The open-panel swipe area now spans the full panel width down through the clock/date row and the QS edit, power and settings buttons. Taps still replay the original DOWN followed by UP to Samsung, so the existing button actions handle them. A downward movement past the native touch threshold switches panels and consumes the rest of that stream without dispatching a button click. Horizontal and upward handling remain as below.

The added boundary is computed only on touch-down:

- On the native notification/legacy header, use the visible `quick_qs_date_buttons` view's bottom on screen.
- On the One UI 9 Compose QS header, use Samsung's `compose_header_clock` and `compose_header_date` AndroidViews and `sec_qs_buttons_container_height`. The APK's `HeaderContainer` centers the clock/date Row in this container; its clock has 1dp top padding and its bottom-aligned date has `date_text_bottom_padding`. These measured positions and resource dimensions include the whole row without using a fixed screen coordinate or percentage.
- Hidden, detached, unmeasured and off-screen views do not extend the area. If the new views/resources are unavailable, the original status-header region remains.
- During a panel transition, reuse the last settled boundary for the same window and panel dimensions. Animated header positions must not extend the hit-test into the panel body. Detach/configuration changes discard the cached boundary.

Additional device checks: swipe down from the date text and all three button locations in both panels; tap edit, power and settings without moving; repeat with slight finger movement below touch slop; verify that QS tiles and notification cards below the row retain their normal gestures. Actual device input delivery remains unverified by static APK inspection and JVM tests.

The remaining sections describe the inherited behavior; references there to the old status-header-only boundary are superseded by the area-increase rules above.

This branch is derived from main `078579d56002c205941cb2977c6d3fafb85c0c18`. It adds only the gesture implementation, regression tests, version/description changes and this documentation. The media-wave fixes and build workflow are inherited.

## Behavior

| Starting state | Gesture | Result |
| --- | --- | --- |
| Both panels closed | Status-bar downward pull | Entirely Samsung routing: left Notifications/right QS, reversed by Quick panel on the left side. |
| Notifications open | Downward pull from anywhere across the existing native header/status-bar swipe area | QS immediately |
| QS open | Same downward pull | Notifications immediately |
| Either open | Another downward pull | Repeat indefinitely, one switch per physical gesture |
| Either open | Horizontal panel-switch swipe | No panel switch |
| Either open | Upward swipe | Forward to Samsung's existing close handling |

No new view/strip is created. The native vertical boundary is the measured height of `ShadeHeaderController.header`, the same boundary read by `PanelSlideEventHandler.initiateSlide`. Event coordinates are translated into `SecPanelSplitHelper.panelRootView` coordinates. X is no longer divided while open. Closed routing and Samsung's reversed/RTL calculations are untouched. Normal notification dismissals, QS sliders and other component gestures outside the switch area remain native.

Each switch runs Samsung's **own animated vertical slide**, so the leaving panel fades out towards the bottom and the arriving panel fades in from the top exactly as for a stock finger-released second pull. It does not close/reopen the shade window. (2.1.x called `slide$1(int)`, which commits the state and then renders a single final frame with `SecQSImplAnimatorManager.slide(1.0f, 0f, DOWN, state)`; that is an instant jump with no transition, and is now used only to settle an interrupted animation.)

## How the animated switch works (verified in the supplied One UI 9 classes*.dex)

Samsung already implements the vertical second pull; the module only supplies the trigger from anywhere in the header.

1. On every touch-down `PanelSlideEventHandler.initiateSlide` sets `direction = UNDECIDED` and `SecPanelSplitHelper.stateOnDown = currentState`.
2. When a vertical pull is released, `SecPanelSplitHelper.handleTouch` calls `PanelSlideEventHandler.createSlideAnimatorAndRun(velocityY, draggedDistance)` with `direction = DOWN`, then clears `tracking`, `panelSliderIntercepted`, `slidingInitialized`, `fullyExpandedOnDown` and `activePointerId`.
3. `createSlideAnimatorAndRun` treats `DOWN` with a non-negative velocity as "forward"; it commits (animates to `+maxDragWidth`) when the speed exceeds `FlingAnimationUtils.mMinVelocityPxPerSecond` or the distance exceeds half of `maxDragWidth`, and takes its duration from `FlingAnimationUtils.apply`. `maxDragWidth` and `SecPanelSplitHelper.getMaxSlideDistance()` are both the screen width.
4. Every animator frame calls `SecPanelSplitHelper.slide(value, DOWN, tracking)`. For `0 < fraction < 1` it moves the panels into `TRANSITION` (state 2) and feeds `SecQSImplAnimatorManager.slide(fraction, overscroll, DOWN, stateToChange)`, which drives the panel transforms. At `fraction == 1.0` it calls `updateState(stateToChange)`, the terminal commit. `updateState` itself derives `stateToChange` from `stateOnDown`.

The module therefore sets `stateOnDown`/`stateToChange`/`direction = DOWN` (the values Samsung holds at a finger release), cancels any previous slide animator, and calls `createSlideAnimatorAndRun(max(4000, 4 x minFlingVelocity) px/s, touchSlop)`. `ValueAnimator.start()` delivers the first frame synchronously, so the result is known immediately: `TRANSITION` or the destination means Samsung accepted; anything else means it refused, and the module cancels the animator and ignores that swipe without disabling itself. A small `AnimatorListener` commits the destination through `slide$1` only if the animation ended while still in `TRANSITION`.

## State and input handling

- Intercept the APK's `NotificationShadeWindowView.dispatchTouchEvent(MotionEvent)` so a new gesture can be handled even while Samsung's panel transition is animating.
- Follow the APK field chain through `mInteractionEventHandler.this$0.mNotificationStackScrollLayoutController.mPanelSplitHelper`. No global guessed helper instance.
- Buffer one DOWN event only when an open split panel's native header is touched. Once movement exceeds Samsung's existing touchSlop, classify its initial direction.
- A downward gesture commits one switch and consumes its remaining events. It never dispatches that DOWN into the native expansion/collapse handler. Thus its eventual UP cannot become a collapse or second toggle.
- An upward gesture or tap replays its original DOWN to native dispatch, followed by the current event; the remaining stream stays native. Horizontal-first header drags are consumed, even if later curved down.
- Read `currentState` on every switch. If a new swipe arrives while an earlier switch is still animating (state TRANSITION), that earlier switch is snapped to its destination first (instant, via `slide$1`), then the new animated switch starts from it. Merely touching the header never does this: the touch-down check is read-only. There is no module-owned visible-panel boolean or unbounded queue.
- Cancel the old native ValueAnimator synchronously on the UI thread and clear its tracking/interception/pointer/velocity flags before starting the next one; Samsung's own methods own listener/repository/Compose/visibility updates.
- The module draws and schedules nothing itself: no timers, delayed toggles or worker threads. The only animation is Samsung's own, and a later swipe sees Samsung's newly committed state.
- Lost UP/CANCEL is cleared on the next DOWN. Detach/configuration changes recycle buffered events. Multi-pointer input before commitment returns to Samsung; a committed gesture consumes its tail.
- All contracts are resolved before hooks install; a registration failure rolls back the entire gesture hook group. Unexpected runtime contract failures (a reflection error or exception) disable the gesture experiment for that process and drain any owned input stream. A single refused switch is not a contract failure: it is ignored and logged once.

## Safest assumptions and limits

1. Applies to the S25's **unlocked, separate-panel mode**. Keep Samsung's behavior on the keyguard, combined shade, tablet pop-over mode, disabled panels and detail/customizer screens. Bypassing those native policies was not justified by the APK alone.
2. A switch is accepted when downward movement first exceeds the native touch slop, rather than after an arbitrary swipe distance. The rest of that gesture is consumed even if the finger reverses direction.
3. Treat the APK's header-height boundary as the requested existing status-bar swipe area. Its exact physical dimensions on the current theme/DPI/orientation need device verification.
4. Header taps are replayed at release; pressed feedback/long-press behavior and interaction with other modules that also intercept dispatch need device testing.
5. No phone was connected and no Android build tooling was reachable when 2.2.0 was written. The members it calls were checked statically against the supplied `classes*.dex` files (see below) and the state logic by JVM tests, but the APK itself is only built by CI, and real Android input delivery, animation smoothness and battery effects are unverified. No claim of zero stutter or zero battery overhead is made.
6. The commit speed (4000 px/s, at least 4x Samsung's minimum fling velocity) only decides how quickly the animation plays; it is a tuning constant, not a Samsung value. Change `COMMIT_VELOCITY_PX_PER_SECOND` in `NativePanels` if the switch feels too fast or slow.
7. API 102 support in the installed LSPosed build must be confirmed on-device. Both APK variants require the repository keystore secrets; builds do not fall back to a test certificate.

## Validation and device checklist

The JVM tests exercise one-toggle-per-stream, horizontal/curved gestures, upward handoff, touch slop, 10,000 consecutive switches from either panel, 5,000 externally changed/transition states, and invalid/combined states. These test the production classifier and state reducer, not Samsung's real runtime.

On the supplied phone verify:
- Both sides of closed shade, with Quick panel on the left side OFF then ON.
- 20+ consecutive header down swipes from each half, starting from each panel.
- Rapid swipes while opening and immediately after a switch; no blank/combined/stale page.
- Upward close from each panel; horizontal header drags leave the current panel unchanged.
- Notification scrolling/dismissal, QS/media sliders, taps, multi-touch and cancellation.
- Rotate, lock/unlock, restart SystemUI and repeat.
- Capture comparable Perfetto traces before/after to assess stutter and battery impact.

## Rollback

Disable the module and reboot for stock behavior. Install the main APK and reboot to retain wave sampling only; a lower versionCode or different signing certificate may require uninstalling the experimental APK first.

All gesture changes are in this branch's commit. Revert that commit for source rollback; main remains unchanged. See [CHANGELOG](../CHANGELOG.md) and [APK evidence](ONEUI9-ANALYSIS.md).

## Diagnostic build 2.1.1

This build investigates the report that the enabled module leaves panel behavior unchanged; it does not claim a gesture fix. Install app-debug.apk, restart SystemUI, then immediately try one horizontal panel switch and one downward header swipe. Export the LSPosed logs with the framework version header included.

The debug APK logs at INFO priority using the OneUI-QSlagfix tag:
- Module process, module/framework versions and package-loaded/package-ready lifecycle events.
- Successful hook registration or its exception.
- At most eight initial root DOWN events, including helper presence, enabled/expanded flags, native panel/status states, pop-over/detail policies, root attachment and header membership.
- The first native interception and slide calls.

There are no continuous frame logs or timers. The verbose diagnostics above only run when `BuildConfig.DEBUG` is true (the debug APK). Both APKs now also write a few INFO status lines per SystemUI start (`Loaded in SystemUI, module version ...`, `Legacy/Colorful wave sampling installed`, `Panel gesture hooks installed`) and WARN lines on failure (`Panel gesture hooks NOT installed`, `Panel gestures disabled until SystemUI restarts`, `Samsung declined a panel switch`), so a release build can be diagnosed from the LSPosed log: if the `Loaded in SystemUI` line is missing the module never loaded; if `Panel gesture hooks installed` is missing, the WARN line next to it names the failing contract.

## Static verification of the Samsung members

`scripts/verify-dex-contracts.py` checks every class, field, method and static flag in `docs/systemui-hook-contracts.json` against raw dex files, with only the Python standard library:

```sh
python3 scripts/verify-dex-contracts.py classes.dex classes2.dex classes3.dex classes4.dex classes5.dex
```

It passed for all 15 recorded classes against the One UI 9 dex files supplied with the 2.2.0 fix, and fails when a class or member is missing. Re-run it after a firmware update before trusting the gesture. It checks that members exist with the right types, not how Samsung's code behaves; the behaviour described above was read from the bytecode of that firmware.

## Release build and R8 (2.2.0)

The release APK reported "enabled but panel behavior unchanged" while the debug APK worked. The only `BuildConfig.DEBUG` uses are log statements, so R8 (shrink/obfuscate/optimize) was the one step that changed the module's executed code. The exact R8 effect was not isolated, so 2.2.0 disables minification and resource shrinking for release: it now goes through the same D8 path as debug. It is still non-debuggable and signed with the same key. `scripts/verify-apks.py` now checks that the release dex contains the status lines above instead of checking that they are absent.
