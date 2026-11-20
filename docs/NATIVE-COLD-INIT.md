# 2.6.6 Native Cold Init — first horizontal swipe

## User goal

The 2.6.4 `v2-glass-fade-eventperf` build is almost perfect once warm, but its **first horizontal swipe** after SystemUI restart, first unlock or >10 minutes of panel inactivity is noticeably less fluid. Avoid sacrificing the full-width crossfade, Samsung QS/notifications UI, native gesture handling, blur, media controls, clock/date or existing 6.82%-baseline optimizations.

This branch is derived **directly from v2-glass-fade-eventperf**, and does not include the 2.6.5 synthetic GPU prewarm (which failed to establish a reduction in cold-frame jank).

## Source verification

Methods were verified from the user's actual One UI 9 SystemUI APK, SHA-256 `81a9e975a9a88a92a94f855c1c4a69dc68dcff28b5e28adfac5adc8ebebc68b4`:

`com.android.systemui.qs.animator.PanelTransitionAnimator`:
- `setQs(QS)`
- `updateAnimators()`
- `destroyQSViews()`
- `onPanelTransitionStateChanged(PanelTransitionStateChangeEvent)`
- `slide(float,float,Direction,int)`

Before this branch, the first active `slide` callback allocated a session and called `HorizontalTransitionPanels.bindSession`: this uses `kotlin.Lazy.getValue`, reflection to load compose/split-helper objects and wrapper views, and getters to unwrap multiple `QSAnimView` instances. The first frame additionally pins `SecQSPanelComposeAdapter.slideFraction` and walks drawable ancestors/Compose children in `prepareDrawables`. This source makes synchronous first-frame binding a **plausible** latency contributor, not a proven cause of the occasional Vulkan shader compile or cold Compose initialization.

## Implementation

1. Hook Samsung `PanelTransitionAnimator.updateAnimators`. After the native method has completed, schedule a one-shot reference prebind for **48 ms later on the main thread**, not within that native initialization frame.
2. Hook `destroyQSViews` to invalidate the prepared graph immediately. A stale scheduled prebind is canceled.
3. When native `onPanelTransitionStateChanged` settles to QS (state 0) or notifications (state 1) **without an active ShadeSwitch session**, pass the full Samsung callback through, then schedule read-only reference prebind. This gives the module a path to prepare references after the first visible panel opening, before the first horizontal swipe.
4. The deferred job first verifies `kotlin.Lazy.isInitialized()`. It **never** forces Samsung's split helper to initialize early. It refuses to run during any horizontal session and while native transition state is changing.
5. The deferred job constructs an **inactive** `HorizontalSwipeSession` containing only resolved object/view references. It does not change visibility, translation, opacity, clipping, Compose state, measured sizes, or accessibility.
6. On the first horizontal `slide`, validate QS/shade anim-view wrapper identities and compose adapter against current Samsung fields. If identical, reuse the prepared session, **skipping native reference binding inside the first animation frame**. Otherwise discard it and execute the original slow path. Actual `begin()`, Compose pin, `prepareDrawables`, native alpha/translation and restoration still occur at exactly the original moment.
7. Preserve a successfully settled session as a read-only cache for subsequent swipes; invalidate on native view rebuild/destruction. All caches and pending callbacks are cleared on any existing hook failure.
8. Add visible Perfetto slices for `ShadeSwitch:prebindNativePanelRefs`, `ShadeSwitch:firstSwipeSessionSetup`, `ShadeSwitch:firstSwipeComposePin` (first frame only) and `ShadeSwitch:firstSwipePrepareDrawables` (first frame only). The first five setup durations/cache-hit counts are **logged 1s later**, never synchronously on the first frame.

## What this does not promise

- It cannot guarantee that the actual first-swipe lag comes from Java reflection rather than first-visibility Compose recompose, HWUI/Vulkan pipeline creation, or GPU contention.
- It does not proactively recompose QS, persist graphic pipelines, keep panels visible, or force a real view's first draw. Those more invasive ideas risk affecting visuals, privacy, battery, or state consistency.
- Unit tests validate the stable-state/lazy-ready gating but cannot prove on-device frame improvement.
- Jank rates must be benchmarked with matched idle intervals and a trace that actually covers first native panel open and the first horizontal swipe.

## Test steps

1. Install the new **signed release APK** built from this branch, keep Vertical swipe OFF, and restart SystemUI. Keep GlassUI disabled as a separate LSPosed module.
2. Unlock once and open notifications or QS, then perform the first horizontal swipe. Verify the fade/clock/media, gesture reversal, notification expansion and final panel state.
3. Check log **Native cold init: first swipe setup ... ms; cache hits=...** (logged after 1 second). A cache miss indicates reference prebinding wasn't available before that particular gesture; existing hook behavior is preserved.
4. Record Perfetto including first pull and first horizontal slide. Look for `ShadeSwitch:prebindNativePanelRefs` before `ShadeSwitch:firstSwipeSessionSetup`. Review the gap and cost of `ShadeSwitch:firstSwipeComposePin` and `ShadeSwitch:firstSwipePrepareDrawables`.
5. Repeat after >10min idle both locked and unlocked and after SystemUI restart, ensuring visual behavior and false-gesture paths still work.
6. If it feels worse, install the original 2.6.4 `v2-glass-fade-eventperf` signed APK. The base branch and its animation remain untouched.

## Security and performance safeguards

- No extra long-lived background thread, offscreen GPU renderer, persistent wakelock or polling timer.
- No early mutations of any SystemUI view or Compose `slideFraction`.
- Read-only reference preparation requires settled state and already-initialized split-helper.
- Native methods always execute; invalid cached wrappers always fall back to ordinary synchronous binding.
- Instrumentation does not log per frame. Delayed human-readable logs are capped.
