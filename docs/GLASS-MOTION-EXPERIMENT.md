# GlassUI-inspired native motion experiment

## Scope and source findings

Analyzed user-supplied `GlassUI-2.3.4.apk`, specifically `classes2.dex`:
- `dev.colorcc.SideSlide` is a Choreographer-driven spring/host animator with
  `SideSlide$Host` implemented by `Panel$3`, an adapter to GlassUI's custom
  control-centre views. The `SideSlide` host is **not** a generic interface to
  Samsung's two stock shade pages.
- `dev.colorcc.PageSwipe.onSlide` hooks
  `com.android.systemui.qs.animator.PanelTransitionAnimator.slide` and
  adjusts native notification rows with `setTranslationX` and
  `setAnimationMatrix` **when GlassUI replaces the stock grid**.
- GlassUI additionally hooks `PanelSlideEventHandler.createSlideAnimatorAndRun`
  to adjust the slow-swipe-over-halfway settling decision. That is separate from
  its spring animation and is not necessary to replicate the visual timing.
- GlassUI may also park inactive stock views. Its measured performance advantage
  cannot be attributed to its spring interpolation alone.

Its repository is: https://github.com/AurelioMo/glass-ui
We do **not** redistribute its bytecode or bundle any of its classes or assets.

## Five integration decisions

1. **Motion model:** A small independent critically damped, endpoint-normalized
   spring step-response (`NativeSpringCurve`). An actual GlassUI spring host
   cannot simply be copied without replacing Samsung's view tree.
2. **Native host adapter:** Hook only the verified One UI 9
   `PanelSlideEventHandler.createSlideAnimatorAndRun(float,float)`,
   reading its `direction` and `sliderAnimator:ValueAnimator` fields.
3. **Samsung owns state:** Run the original method first, then change only its
   horizontal (`LEFT` or `RIGHT`) animator's interpolator. Samsung's
   original touch events, animator update callbacks, frame timestamps,
   state transitions, listener completion, and notification/QS views are retained.
4. **No competing animations:** The stock ValueAnimator already runs from
   Choreographer. We do not post another frame callback, create another animator,
   translate/alpha-set any panel, modify Compose state, or alter fling distance.
5. **No unrelated UI:** Nothing touches GlassUI tiles, sliders, media card,
   blur, dynamic island, or style settings. Vertical mode still uses the exact
   v2/area-increase header-only hooks; Together quick-QS remains independent.

## What this experiment can and cannot improve

It can change **release/settle feel** while maintaining the native UI. It does
**not** accelerate Compose, SurfaceFlinger, the media-player renderer or the GPU.
It does **not** completely transplant GlassUI's custom panel animation, and
matching GlassUI's Perfetto missed-frame rate is **not** expected from changing
the interpolation alone. That would require further profiling and potentially
a separate reversible rendering optimization.

The experiment is enabled when the existing **Vertical swipe to switch panels**
toggle is OFF. When ON, only `area-increase` hooks are registered for gestures.
If SystemUI method/field contracts differ, the installation fails closed and
stock horizontal animation remains.

## Validation checklist

- With vertical toggle **OFF**, swipe horizontally (both directions), pause,
  reverse, short-flick and long-flick with notifications and media player visible.
- Check clock/date, status icons, notification row taps, back/close gestures,
  full Quick Settings, sliders, edit/power/settings buttons, and lockscreen.
- With vertical toggle **ON**, verify the original header-only downward pull;
  confirm no native spring hook is installed simultaneously.
- Disable GlassUI in LSPosed for this experiment, or both modules could hook
  `createSlideAnimatorAndRun` and make the result meaningless.
- Record matched Perfetto traces to compare release smoothness against v2
  baseline. If the stock frame drops remain, stop tuning the motion curve and
  address the measured rendering bottleneck independently.

## Rollback

Install signed `v2` APK from its branch at the same version code and restart
SystemUI. Both builds retain the same Android package identifier and LSPosed
RemotePreferences keys.
