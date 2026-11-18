# ShadeSwitch

An LSPosed module (`io.github.libxposed.api:101.0.0`) for **Samsung One UI 9 / Android 17** (`com.android.systemui`) that replaces Samsung's delayed cross-fade and `±300px` slide between **Notifications** and **Quick Settings** with a **GlassUI-style rigid horizontal page transition** driven directly by Samsung's native drag progress.

> **Branch Architecture**
> - `tgtr` adds **Together style → Hide Quick QS row** in ShadeSwitch settings. Enable it in LSPosed, restart SystemUI, and select **Together** in Samsung’s Panel settings. The first pull shows the notification panel without the compact tile row. A second pull opens full Quick Settings. Samsung’s **Brightness control** setting governs the slider.
> - `cust`: Keeps Samsung's native horizontal gesture enabled by default and drives both pages with rigid full-width translation and a symmetric `FADE_CUTOFF = 0.45f` outgoing fade while keeping the stock Compose Quick Settings scene drawable throughout the drag.
> - `area-increase`: Separate vertical-only variant (`NoSwipePanelHooks`) that disables horizontal panel switching and splits top-down pull zones at `60%` / `40%`.

---

## Behavior on `cust`

1. **Native Horizontal Drag & Reversed Panel Order**:
   - In normal panel order (`SecPanelSplitHelper.isReversed() == false`), swiping left brings **Quick Settings** in from the right (`+width -> 0`) and moves **Notifications** to the left (`0 -> -width`); swiping right brings **Notifications** in from the left (`-width -> 0`) and moves **Quick Settings** to the right (`0 -> +width`).
   - Respects Samsung's reversed panel order (`isReversed() == true`).
   - Preserves Samsung's native gesture ownership for QS tile paging, notification dismissal, brightness/volume sliders, header taps, and shade closing, plus Samsung's native release/reversal/fling completion logic (`PanelSlideEventHandler.createSlideAnimatorAndRun`).
2. **Symmetric Outgoing Fade (`FADE_CUTOFF = 0.45f`) & Immediate Incoming Visibility**:
   - The incoming page renders at full opacity (`1.0f`) on the first frame it enters the screen.
   - The outgoing page begins fading on the first visible movement (`progress > 0f`) and reaches `0.0f` opacity at `HorizontalTransitionMath.FADE_CUTOFF = 0.45f` (`45%` incoming drag progress) in both directions, continuing rigid translation after the fade finishes.
   - Holding mid-swipe holds both pages in place; reversing finger movement moves both pages back together and restores outgoing opacity along the same curve.
3. **Stock Compose Quick Settings Render Fix**:
   - In stock One UI 9 `SystemUI.apk`, `SecQSPanelComposeAdapter.setSlideFraction(slideFraction)` drives `QuickSettingSceneViewModel` (`isPresenting = !collapsed && slideFraction < 1.0f`, `alphaOfScene = min(expFactor, 1f - slideFraction)`) and `SecQuickSettingScene` (`translationYOfScene = -5000f` when `slideFraction == 1.0f`, `translationXOfScene = slideFraction * 300f`).
   - During an active horizontal transition (`panelTransitionState == TRANSITION_STATE`), `HorizontalPanelTransitionHooks` pins `composeAdapter.setSlideFraction(0.0f)` and keeps `qsRootView` (`SecQSPanelComposeRoot`), its `ComposeView` child, and its ancestors `View.VISIBLE` so QS tiles, sliders, and media controls are already visible during the drag.
   - When the transition settles (on completion `fraction == 1.0f`, cancellation/reversal `fraction == 0.0f`, `clearAnimationState()`, or `onPanelClosed$1()`), native visibility, alpha, translation, and settled `slideFraction` (`0.0f` for `QS_STATE`, `1.0f` for `SHADE_STATE`) are restored.
4. **Media-Wave Renderer Review**:
   - Removed the legacy/colorful media-wave `stepX` coarsening hooks (`stepX = 10` / `8`) from `cust` because stock One UI 9's `MultiWaveAreaTrackRenderer` already early-outs when idle and caches its gradients/xfermodes; raising `stepX` only coarsened the waveform without improving panel transition smoothness. `SamsungMusicWavesHook` remains the Xposed entry point.
