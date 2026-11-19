# Changelog

## v2 — clean area-increase restart

- Started `v2` from the existing `area-increase` branch.
- Brought over the fixed settings UI and independent **Together style → Hide Quick QS row** setting from `tgtr`.
- **Vertical swipe ON:** preserved `area-increase` header-only gesture hooks without bringing over the unsuccessful notification-body gesture interception.
- **Vertical swipe OFF:** preserve Samsung's stock horizontal gestures and animation. **No** GlassUI-style horizontal transition/fade code.
- Removed legacy media-wave sampling hooks from the installed startup path for clean stock rendering comparison.
- Preserved existing remote preference keys and restart behavior; existing installations retain their selections.
- Native horizontal swipe jitter, notably with media playback, remains to be profiled and optimized from device traces.
