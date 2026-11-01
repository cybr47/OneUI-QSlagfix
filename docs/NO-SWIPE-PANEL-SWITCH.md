# Common header drag-and-release — area-increase

Version 2.3.0-native-drag replaces the automatic panel toggle on this branch with Samsung's native interactive vertical gesture. The source branch no-swipe-panel-switch keeps its existing behavior.

## Behavior

| Starting state | Input | Result |
| --- | --- | --- |
| Both panels closed | Pull down from status bar | Samsung's original left/right/reversed routing |
| Notifications or QS open | Pull down anywhere across the expanded clock/date/button row | The other panel moves with the finger using Samsung's transition |
| Mid-drag | Hold still | The panel stays at the finger-controlled progress |
| Mid-drag | Move back toward the starting position | The transition reverses with the finger |
| Mid-drag | Release | Samsung's original distance/velocity logic completes the change or returns to the starting panel |
| QS open | Tap edit, power or settings | The original Samsung button action |
| Either panel open | Horizontal-first panel-switch drag | No panel switch; component touch handling remains native |
| Either panel open | Upward close gesture | Samsung's original close handling |

A native release animation in progress follows Samsung's policy for new touches. The module no longer snaps an earlier animation to its destination or forces another change.

## Start area

The expanded boundary from 2.2.1 is retained across the full panel width:

- Native notification/legacy row: the visible quick_qs_date_buttons view's screen bottom.
- Compose QS row: Samsung's compose_header_clock / compose_header_date AndroidViews, centered Row layout and sec_qs_buttons_container_height. Clock top padding and date_text_bottom_padding are included.
- Hidden, detached, unmeasured and off-screen views do not extend the region. The original status-header region remains available.
- During a transition, reuse the last settled boundary for the same window and panel dimensions. Moving headers cannot expand the region into notification cards or QS tiles. Configuration changes and detach clear that cache.

## Reusing Samsung's touch engine

Verified in the supplied One UI 9 APK with SHA-256 81a9e975a9a88a92a94f855c1c4a69dc68dcff28b5e28adfac5adc8ebebc68b4:

1. SecPanelSplitHelper.onIntercept calls PanelSlideEventHandler.initiateSlide on DOWN. Samsung initializes the real pointer, initial coordinates, velocity tracker and stateOnDown. After that initialization, the module replaces only isInChangeSpotOnDown with membership in the full expanded header.
2. Samsung also independently checks initialY against ShadeHeaderController.header.getMeasuredHeight in updateDirection and onIntercept. Changing the start-area flag alone would therefore leave the date row excluded.
3. For an eligible MOVE from the expanded row, the module lets slidePointerDeltas calculate its native Pair of real X/Y deltas first. Only then is initialY temporarily set to -1 within the synchronous onIntercept call, satisfying those header-height eligibility checks. A finally block restores the real origin before native touch handling runs. Repeated/nested delta reads first restore the actual origin too. MotionEvents, pointer IDs, timestamps, coordinates, computed deltas and velocity samples are not rewritten.
4. Native onIntercept still decides whether to intercept using its expansion, touch-slop, keyguard, navigation-gesture, Compose and detail-screen policies. Its original intercept callback runs. Android's normal parent interception cancels the pressed child button when a drag takes over.
5. SecPanelSplitHelper.handleTouch receives the original MOVE stream. Its native slidePointerDeltas, updateDirection and slide calls follow the finger. Direction.DOWN remains locked by Samsung while the finger moves back or diagonally.
6. Native UP/CANCEL handling and createSlideAnimatorAndRun settle the drag using Samsung's own distance, velocity, interpolator and cancellation rules. The module supplies no fling speed and never starts, completes or draws an animation.

There is no root touch-dispatch replacement, buffered DOWN, tap replay, automatic toggle reducer, visible-panel boolean, transition-snapping listener, timer, worker thread or custom animation.

## Horizontal switching

A per-stream gate blocks horizontal-first direction classification before Samsung intercepts a panel switch. It does not consume component MotionEvents. A gesture that began horizontally cannot curve into a panel switch later in the same stream. An already established native DOWN drag is left alone, including reversal and diagonal motion.

Session state is reset by native initialization and cleared on release/cancellation, configuration changes and detach. Native method exceptions propagate normally. Unexpected failures in the module's reflection disable its overrides for that process and log a warning.

## ART and framework requirements

Modern libxposed API 102 is required. All reflection contracts resolve before hooks register; a registration failure rolls back the group. SecPanelSplitHelper.onIntercept is explicitly deoptimized so ART cannot bypass the hooked initiateSlide, slidePointerDeltas and updateDirection through inlining. Live handleTouch, renderer and release-animation methods are not explicitly deoptimized or replaced.

Verbose diagnostics are debug-only and bounded to the first eight initial touches. Both APKs retain the short module-loaded and hook-install status lines, plus warnings on failure.

## Validation and phone checks

The JVM regressions cover real-origin restoration before release and after exceptions, repeated delta reads, origin changes from pointer handoff, unmodified taps, horizontal-first rejection and preserving native DOWN drags during reversal. They test the adapter, not a recreated Samsung animation.

The recorded class/field/method/static contracts are checked against all five supplied DEX files with:

    python3 scripts/verify-dex-contracts.py classes.dex classes2.dex classes3.dex classes4.dex classes5.dex

CI runs the JVM tests, debug/release lint, both APK builds, package checks and repository-key signing. No S25 is connected here; these checks do not establish actual input delivery or smoothness.

On the phone, check:

- Start from each side of the date row in Notifications and QS, including the three QS button positions.
- Drag halfway and hold, move back and release, then drag far enough to complete and release.
- Compare slow release, a short fling, upward close and repeated physical gestures with Samsung's stock behavior.
- Tap edit, power and settings, including slight movement below touch slop and native pressed/long-press feedback.
- Check QS sliders, notification scrolling/dismissals, pointer handoff, rotation and lock/unlock.
- Confirm that closed-panel routing still follows Samsung's left/right and reversed settings.

## Install and rollback

Install one branch APK, enable System UI / com.android.systemui scope and reboot. Both variants use the repository signing key; versionCode 7 permits an update over 2.2.x signed with the same key.

Disable the module and reboot for stock behavior. Install main for media sampling only; its lower versionCode may require uninstalling the experimental APK first. A source rollback can revert the native-drag commit without changing the other branches.
