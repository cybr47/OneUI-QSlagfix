# Status-bar swipe toggle branch

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

The switch uses a completed native `slide$1(int)` call instead of a replacement animation. It does not close/reopen the shade window.

## State and input handling

- Intercept the APK's `NotificationShadeWindowView.dispatchTouchEvent(MotionEvent)` so a new gesture can be handled even while Samsung's panel transition is animating.
- Follow the APK field chain through `mInteractionEventHandler.this$0.mNotificationStackScrollLayoutController.mPanelSplitHelper`. No global guessed helper instance.
- Buffer one DOWN event only when an open split panel's native header is touched. Once movement exceeds Samsung's existing touchSlop, classify its initial direction.
- A downward gesture commits one switch and consumes its remaining events. It never dispatches that DOWN into the native expansion/collapse handler. Thus its eventual UP cannot become a collapse or second toggle.
- An upward gesture or tap replays its original DOWN to native dispatch, followed by the current event; the remaining stream stays native. Horizontal-first header drags are consumed, even if later curved down.
- Read `currentState` on every switch. If state is TRANSITION (2), finish the recorded `stateToChange` first, then toggle its terminal destination. There is no module-owned visible-panel boolean or unbounded queue.
- Cancel the old native ValueAnimator synchronously on the UI thread before applying a terminal state. Clear its tracking/interception/pointer/velocity flags; update state through Samsung's method, which owns listener/repository/Compose/visibility updates.
- Do not schedule animations, delayed toggles, worker threads or timers. A later swipe sees Samsung's newly committed state.
- Lost UP/CANCEL is cleared on the next DOWN. Detach/configuration changes recycle buffered events. Multi-pointer input before commitment returns to Samsung; a committed gesture consumes its tail.
- All contracts are resolved before hooks install; a registration failure rolls back the entire gesture hook group. Unexpected runtime contract failures disable the gesture experiment for that process and drain any owned input stream.

## Safest assumptions and limits

1. Applies to the S25's **unlocked, separate-panel mode**. Keep Samsung's behavior on the keyguard, combined shade, tablet pop-over mode, disabled panels and detail/customizer screens. Bypassing those native policies was not justified by the APK alone.
2. A switch is accepted when downward movement first exceeds the native touch slop, rather than after an arbitrary swipe distance. The rest of that gesture is consumed even if the finger reverses direction.
3. Treat the APK's header-height boundary as the requested existing status-bar swipe area. Its exact physical dimensions on the current theme/DPI/orientation need device verification.
4. Header taps are replayed at release; pressed feedback/long-press behavior and interaction with other modules that also intercept dispatch need device testing.
5. No phone is connected. Static contracts, regression tests, release stripping, compilation and lint are checked, but real Android input delivery, visual smoothness and battery effects are unverified. No claim of zero stutter or zero battery overhead is made.
6. API 102 support in the installed LSPosed build must be confirmed on-device. Both APK variants require the repository keystore secrets; builds do not fall back to a test certificate.

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
