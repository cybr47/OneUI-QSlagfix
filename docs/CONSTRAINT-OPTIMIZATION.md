# Glass Fade — targeted native ConstraintSet coalescing

## Scope

This branch starts from the visually preferred **v2-glass-fade** build (8.81% full/partial surface-jank in its earlier trace), **not** from the `fcc6466` hot-path experiment that felt worse to the user. The original `v2-glass-fade`, `v2-glass-fade-perf`, and `v2` branches remain unchanged.

The tested fade formula, Samsung panel-view transforms, notification blur backgrounds, media player, header, widgets and input handling are byte-for-byte unchanged by this change, except for a narrowly scoped extra interception of one Samsung listener.

## Source evidence (actual One UI 9 binary)

Source: uploaded **SystemUI.apk**, SHA-256:

`81a9e975a9a88a92a94f855c1c4a69dc68dcff28b5e28adfac5adc8ebebc68b4`

DEX: `classes3.dex`. The following specific bytecode calls have been inspected:

1. `com.android.systemui.shade.SecPanelSplitHelper.setDraggedFraction(float)`: updates `draggedFraction`, allocates a `PanelTransitionStateChangeEvent` and invokes **every** `PanelTransitionStateListener.onPanelTransitionStateChanged` whenever the fraction changes.
2. `SecNotificationsQSContainerController$panelTransitionStateListener$1.onPanelTransitionStateChanged(event)`: tests whether the container is attached, creates `$panelTransitionStateListener$1$2`, then calls `view.post(runnable)`. **It does not inspect the event's fractional value.**
3. `$panelTransitionStateListener$1$2.run()`: invokes the stored `updateConstraints` lambda.
4. `NotificationsQSContainerController$$ExternalSyntheticLambda0.invoke()`: invokes `NotificationsQSContainerController.updateConstraints$1()`.
5. `NotificationsQSContainerController.updateConstraints$1()`: creates and clones an `androidx.constraintlayout.widget.ConstraintSet`, computes header/margins and QS/notification sizes, and calls `ConstraintSet.applyTo`. It reads *layout and configuration state*, but does **not** read `draggedFraction` or the event's fraction.

This means hundreds of identical transient events can request repeated full constraint rebuilds during a horizontally sliding page, even though **the constraint-producing function does not depend on that event's fraction**. The latest ftrace contained 666 instances of `SecNotificationsQSContainerController$panelTransitionStateListener$1$2`, median ~0.15ms per runnable. A missed frame cannot automatically be attributed to those calls, but removing their duplicate scheduling is a source-grounded, bounded experiment.

## Implementation

One additional optional hook is installed on:

`SecNotificationsQSContainerController$panelTransitionStateListener$1.onPanelTransitionStateChanged(PanelTransitionStateChangeEvent)`

Only when a ShadeSwitch horizontal slide session is active:
- The **first** event in `TRANSITION_STATE` (state 2) is always passed through.
- Later `TRANSITION_STATE` events during that same active horizontal session are skipped **only for this one listener**.
- **Every** QS, SHADE, and COMBINED completion event is forwarded, resetting the gate.
- **All** listener calls while not actively switching horizontally pass through.
- `onLayoutChange`, configuration/window-inset updates, other listeners, and their native callback paths are untouched.

The extra hook is optional: if its class/method differs on another firmware build, the existing Glass Fade animation remains enabled; module startup is not blocked.

No Compose/blur/hardware layers are disabled, no force-hiding views, no extra frame loop or custom animation is added.

## Performance expectations & validation

Possible improvements include fewer pointless `View.post` messages, fewer `ConstraintSet.clone/applyTo` operations, and fewer layouts during active horizontal swipes. This branch has not yet run on the Galaxy S25; **no FPS improvement is claimed from source inspection or successful CI**.

In LSPosed release logs, look for:
- `Native transition constraint coalescer installed`
- `Native constraint coalescer: skipped N redundant rebuild requests during horizontal gesture`

With Vertical swipe OFF and GlassUI disabled, test first pull, horizontal left/right, fast reversals, QS edit/settings, sliders, notifications/media card, shade close, lockscreen, and rotation. The first pull from a fully closed shade may have separate cold Compose work; this hook deliberately does not suppress initialization or first layout work.

Record a matched Perfetto trace against `v2-glass-fade` and inspect `SecNotificationsQSContainerController$panelTransitionStateListener$1$2`, traversal, Compose recompositions, and NotificationShade jank. Revert immediately to the signed `v2-glass-fade` build if any layout, clock, notification, or interaction regresses.
