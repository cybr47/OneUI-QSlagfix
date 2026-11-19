# Changelog

## Unreleased — horizontal fade timing

- Outgoing panel now fades with a symmetric smoothstep across 100% of the horizontal swipe instead of disappearing halfway through. At 25%, 50%, and 75% translation its opacity is 84.375%, 50%, and 15.625% respectively.
- Keep the incoming page fully opaque and preserve horizontal translation, per-notification blur alpha, native slide/release handling, and the separate vertical-swipe option.

## 2.4.0-cust-horizontal (versionCode 10)

- Replaced `NoSwipePanelHooks` (inherited from `area-increase`) on `cust` with `HorizontalPanelTransitionHooks` and `HorizontalTransitionMath` so Samsung's native horizontal Notifications <-> Quick Settings swipe runs by default.
- Implemented rigid full-width page translation and symmetric outgoing opacity fade using `HorizontalTransitionMath.FADE_CUTOFF = 0.45f` in both directions while keeping the incoming panel at `1.0f` opacity and keeping shared background, status icons, carrier text, and navigation handle stationary.
- Fixed the stock One UI 9 Compose Quick Settings blank/delayed render bug during horizontal drags by pinning `SecQSPanelComposeAdapter.setSlideFraction(0.0f)` while `panelTransitionState == TRANSITION_STATE (2)` and restoring settled `slideFraction` (`0.0f` for `QS_STATE`, `1.0f` for `SHADE_STATE`), visibility, alpha, and translation on both completion (`fraction == 1.0f`) and cancellation/reversal (`fraction == 0.0f`).
- Removed the `MultiWaveAreaTrackRenderer` `stepX` coarsening hooks from `cust` after verifying stock One UI 9 media waves are already smooth and early-out when idle; preserved `SamsungMusicWavesHook` as the module entry point and kept custom signing configuration intact.
