# Changelog

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
