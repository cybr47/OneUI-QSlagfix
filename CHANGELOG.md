# Changelog

## v2 — clean area-increase restart

- Started `v2` from the existing `area-increase` branch.
- Brought over the fixed settings UI and independent **Together style → Hide Quick QS row** setting from `tgtr`.
- **Vertical swipe ON:** preserved `area-increase` header-only gesture hooks without bringing over the unsuccessful notification-body gesture interception.
- **Vertical swipe OFF:** preserve Samsung's stock horizontal gestures and animation. **No** GlassUI-style horizontal transition/fade code.
- Removed all legacy media-wave/QS lagfix code from the v2 runtime; its DEX contracts are no longer included and APK packaging now checks that the old sampling hooks are absent. Kept the historical package ID for in-place upgrades.
- Preserved existing remote preference keys and restart behavior; existing installations retain their selections.
- Native horizontal swipe jitter, notably with media playback, remains to be profiled and optimized from device traces.
