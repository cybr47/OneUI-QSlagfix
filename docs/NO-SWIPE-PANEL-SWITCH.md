# Horizontal Panel Transition (`cust`) vs. Vertical Gate (`area-increase`)

## Branch Separation

- **`area-increase` branch**: Uses `NoSwipePanelHooks`, `InitialYGate`, and `SwipeSession` to disable horizontal panel switching between Notifications and Quick Settings while widening the top-down Quick Settings pull zone (`60%` / `40%`).
- **`cust` branch**: Restores Samsung's native horizontal gesture (`SecPanelSplitHelper.onIntercept` / `handleTouch` and `PanelSlideEventHandler`) and hooks `PanelTransitionAnimator` (`HorizontalPanelTransitionHooks`) to provide a GlassUI-style rigid horizontal page transition without bounce, stretch, or per-element springs.

## Reference Opacity Trace (`GlassUI-2.3.4.apk` vs. One UI 9 `SystemUI.apk`)

1. **`GlassUI-2.3.4.apk` (`classes2.dex`)**:
   - `dev.colorcc.Hook$5` hooks `PanelTransitionAnimator.slide` (`afterHookedMethod`) and calls `PageSwipe.onSlide(param.thisObject, param.args)`.
   - `PageSwipe.onSlide` checks `panelTransitionState == 2` (`TRANSITION_STATE`), `direction` in `{"LEFT", "RIGHT"}`, and `overSlideX == 0f`. It computes `controls = (stateToChange == 0) ? fraction : (1f - fraction)` (`0..1` QS shown) and `notifShown = 1f - controls`, zeroes `shadeRoot.setTranslationX(0f)`, and translates visible rows in `R.id.notification_stack_scroller` by `width * (Hook.isReversed() ? 1f : -1f) * (1f - notifShown)`.
   - `SideSlide` and `Panel.applySheet` drive GlassUI's custom QS overlay using `SideSlide.sideAt(page, width) = width * (Hook.isReversed() ? -1f : 1f) * (1f - page)` and `SideSlide.alphaAt(page) = clamp(1.5f * page, 0f, 1f)`.
2. **Asymmetry in the Reference Implementation**:
   - **Notifications -> QS (`stateToChange == 0`, `controls = fraction`)**: Outgoing Notifications is faded by Samsung's `shadeFadeOutLeft`/`shadeFadeOutRight` (`mEndDelay = 0.5f`, fading `1 -> 0` over `fraction in [0.0, 0.50]`), while GlassUI's QS overlay fades in via `clamp(1.5f * fraction, 0f, 1f)` over `fraction in [0.0, 0.667]`.
   - **QS -> Notifications (`stateToChange == 1`, `controls = 1f - fraction`)**: Outgoing QS uses `clamp(1.5f * (1f - fraction), 0f, 1f)`, which remains clamped at `1.0f` for `fraction in [0.0, 0.333]` (`1/3`) and only fades `1 -> 0` over `fraction in [0.333, 1.0]`, while incoming Notifications (`shadeFadeInRight`/`shadeFadeInLeft`, `mStartDelay = 0.5f`) stays at `0.0f` until `fraction = 0.50`!
3. **Symmetric Cutoff in `cust` (`HorizontalTransitionMath.FADE_CUTOFF = 0.45f`)**:
   - Outgoing page alpha in both directions: `outgoingAlpha(progress) = clamp01(1f - (progress / 0.45f))`.
   - Incoming page alpha in both directions: `incomingAlpha(progress) = 1.0f`.
