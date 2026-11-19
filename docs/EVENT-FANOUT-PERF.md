# Source-verified QS animator fan-out experiment

## Baseline and objective

**Baseline:** `v2-glass-fade-constraints`, measured in the user's 2026-11-19 22:02:52 Perfetto trace: 1,187 NotificationShade surface frames, 74 full + 7 partial jank (6.82%), median actual surface frame span 8.822 ms, p95 17.116 ms, p99 35.668 ms, maximum 84.797 ms.

Historical reference: GlassUI full replacement panel recorded 76/1,495 partially/fully janky frames (5.08%), but all recordings have different durations and swipe workloads. Our **target** is to match or beat this reference while leaving Samsung's original QS/Notifications UI and the existing GlassUI-style native crossfade intact. No performance target is promised.

## Actual Samsung binary evidence

Inspected user-supplied **SystemUI.apk** from One UI 9, SHA-256:

`81a9e975a9a88a92a94f855c1c4a69dc68dcff28b5e28adfac5adc8ebebc68b4`

Inside `classes3.dex`, the following decompiled DEX operations were verified:

- `SecPanelSplitHelper.setDraggedFraction(float)` emits one `PanelTransitionStateChangeEvent` for each drag fraction update, causing all registered transition-state listeners to be called.
- `SecQSImplAnimatorManager.onPanelTransitionStateChanged(PanelTransitionStateChangeEvent)` **always** creates a synthetic Consumer (lambda class ID 4), calls its superclass transition-state setter, and invokes `executeConsumer(Consumer)`.
- `SecQSImplAnimatorManager.executeConsumer` uses `mList.stream().filter(...).forEach(...)`. The list holds seven native QS animators, including `PanelTransitionAnimator`, `PanelSplitOpenAnimator`, `LegacyQsOpenAnimator`, `LegacyQsExpandAnimator`, `QsTransitionAnimator` and immersive-scroll variants.
- Disassembly of the child `onPanelTransitionStateChanged` methods shows state/enable processing, with **only** `PanelTransitionAnimator` reading `event.fraction` for its debug string; no child in that direct fan-out uses fraction to update translation or opacity. Actual horizontal slide position is delivered independently via `PanelTransitionAnimator.slide(float,float,Direction,int)`, and ShadeSwitch's existing horizontal hook already owns those visual transforms.
- Several independent listeners (`SecQSImpl`, `SecQuickQSPanelController`, `NotificationPanelViewController$2`) already check for unchanged state/enable before doing work. They are **not hooked** or modified here.

This identifies a second fraction-independent repeated notification path, distinct from the container constraint listener addressed in the previous branch.

## Precise integration

One **optional** extra LSPosed hook:

`com.android.systemui.qs.animator.SecQSImplAnimatorManager.onPanelTransitionStateChanged(PanelTransitionStateChangeEvent)`

During ShadeSwitch-owned active horizontal slides only:

1. Forward the **first** `TRANSITION_STATE` (state 2) event.
2. Skip later state-2 events if the event's `enabled` boolean has not changed.
3. Always forward events where `enabled` changes, and **every** QS_STATE (0), SHADE_STATE (1) or COMBINED_STATE (3) completion event, resetting the gate.
4. Forward **all** non-horizontal, lockscreen, vertical/header-only, startup and other ordinary native panel events unchanged.
5. On unexpected firmware reflection/method-contract failure, **fail open**: call Samsung's original method, not a substitute implementation.

An invocation counter reports the first three gestures with skipped duplicate manager fan-outs; it is not logged on each frame.

## Visual and functional safeguards

- Do not alter `GlassFadeMath.alpha()`, page translation, native view states or touch/gesture recognition.
- Retain the existing, separately functioning constraint coalescer.
- Do not skip `PanelTransitionAnimator.slide`, `SecQSImplAnimatorManager.slide`, native frame callbacks, QS media updates, configuration changes, content updates, accessibility, notifications, clock/date or button behavior.
- Do not remove/hide Compose, hardware layers, blur backgrounds, tiles, sliders, status icons, notification rows, media cards or Dynamic Island.
- Unit tests cover first-event forwarding, repeated-state dropping, enabled toggle, every completion state, non-horizontal passthrough and per-session reset.

**Potential limitation:** A child animator might query external changing state while processing an otherwise identical transient-state event. The static DEX event-field analysis does not prove all such behavior is redundant. Therefore, this is an experimental branch and must not merge into the visually preferred baseline until confirmed on the Galaxy S25.

## How to test

Disable GlassUI as a separate LSPosed SystemUI module, install the signed release APK, leave **Vertical swipe to switch panels OFF**, and restart SystemUI.

First confirm logs:
- `Native transition constraint coalescer installed`
- `Native QS animator fan-out coalescer installed`
- `Native QS animator fan-out coalescer: skipped N redundant transient state notifications`

Then test long and short horizontal swipes in both directions, partial reversal, fast fling, notification expansion, interactive QS volume/brightness, media card/buttons, clock/date, lockscreen, first pull, and several repeated transitions. If any visual discrepancy appears, reinstall the previous signed `v2-glass-fade-constraints` build (same package ID/signing identity).

For performance, record a similarly paced/length-matched Perfetto trace. Compare NotificationShade surface frame jank, p50/p95/p99 spans, app-deadline misses, first-pull latency and CPU/RenderThread metrics, **not** only aggregate jank. The ~5.08% full GlassUI result remains a reference, not a guarantee.
