# Changelog

## v2-glass-fade-native-coldinit (2.6.6) — read-only first-swipe preparation

- Derived from the working 2.6.4 eventperf branch, with no synthetic GPU prewarm.
- Defer **read-only** Samsung animator/view reference binding until just after a settled native QS/notification panel opening; update the cache after native `updateAnimators()`, and invalidate on `destroyQSViews()`.
- Only use a cached graph when Samsung's current QS/shade view wrapper identities and compose adapter still match; otherwise preserve original first-frame reference binding.
- Never force lazy SystemUI initialization, touch actual view properties or Compose slide fractions before a gesture, add a renderer, hold a wakelock or run periodic tasks.
- Add first-frame Perfetto trace points and delayed, capped cache hit/miss timings. Tests guard native state and lazy lifecycle.
- Performance and visual equivalence require on-device testing; the original 2.6.4 branch stays available.


## v2-glass-fade-eventperf — scoped QS animator callback deduplication

- Based on the 6.82% jank `v2-glass-fade-constraints` experiment, with identical fade and native pages.
- Avoid repeated per-fraction Java Stream fan-out in Samsung's QS animator manager when `state` and `enabled` are unchanged during an already-owned horizontal slide.
- Preserve initial, enabled-changing, final, disabled, and non-horizontal transitions; add regression tests and runtime skipped-event counters.
- Keep the existing layout-constraint coalescer intact; no additional drawing, Compose, or media hooks.
- Performance goal: approach GlassUI's 5.08% reference, but **not claimed or proven** until on-device measurements.


## v2-glass-fade-constraints — source-verified native layout optimization

- Based on the visually preferred `v2-glass-fade`; excludes the later `fcc6466` hot-path experiment.
- Inspect actual One UI 9 SystemUI.apk SHA-256 `81a9e975…`:
  the Samsung split helper dispatches a transition event for each drag fraction,
  and one listener posts `ConstraintSet.clone/applyTo` despite never reading the fraction.
- Add an **optional, narrowly scoped listener hook** to forward the first transition event,
  then skip duplicate transient events for that listener only while a horizontal swipe is active.
- Never drop discrete completion, vertical/normal, configuration, layout, media, notification
  blur, Compose or other event listeners.
- Log capped per-gesture skipped rebuild counts, test exact pass-through/reset semantics.
- No new animation curve, layer promotion, spring or view modification.
- Effect on FPS / first-open responsiveness is unverified until on-device trace.

## v2-glass-fade — fade without bounce

- Native full-width horizontal panel slide and GlassUI crossfade; no spring bounce.
