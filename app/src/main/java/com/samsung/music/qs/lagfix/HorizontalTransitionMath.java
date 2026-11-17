package com.samsung.music.qs.lagfix;

/**
 * Pure math and state-calculation helpers for the GlassUI-style horizontal transition between
 * Notifications and Quick Settings on One UI 9.
 *
 * <p>Reference opacity trace summary:
 * <ul>
 *   <li>In {@code GlassUI-2.3.4.apk}, {@code PageSwipe.onSlide} reads {@code fraction} and
 *       {@code stateToChange} from {@code PanelTransitionAnimator.slide(float, float, Direction, int)}
 *       and computes {@code controls = (stateToChange == 0) ? fraction : (1f - fraction)}.
 *   <li>When swiping Notifications -> Quick Settings ({@code stateToChange == 0}), Samsung's native
 *       {@code shadeFadeOutLeft}/{@code shadeFadeOutRight} fades Notifications out with
 *       {@code mEndDelay = 0.5f} (reaching {@code 0f} at {@code fraction = 0.50f}), while GlassUI's
 *       {@code SideSlide.alphaAt(controls) = clamp(1.5f * controls, 0f, 1f)} fades Quick Settings
 *       in over {@code fraction in [0.0, 0.667]}.
 *   <li>When swiping Quick Settings -> Notifications ({@code stateToChange == 1}), GlassUI's
 *       {@code SideSlide.alphaAt(1f - fraction)} stays clamped at {@code 1.0f} until
 *       {@code fraction = 1/3 (~0.333)} and only reaches {@code 0f} at {@code fraction = 1.0f},
 *       while Samsung's {@code shadeFadeInRight}/{@code shadeFadeInLeft} ({@code mStartDelay = 0.5f})
 *       keeps Notifications at {@code 0f} until {@code fraction = 0.50f}.
 * </ul>
 *
 * <p>To provide a symmetric, immediate outgoing fade in both directions while keeping the incoming
 * panel at full opacity ({@code 1.0f}) as soon as it enters the screen, we use the single named
 * cutoff {@link #FADE_CUTOFF} ({@code 0.45f}).
 */
public final class HorizontalTransitionMath {
    /**
     * Named incoming-page progress cutoff at which the outgoing page reaches {@code 0.0f} opacity
     * in both directions ({@code Notifications -> QS} and {@code QS -> Notifications}).
     */
    public static final float FADE_CUTOFF = 0.45f;

    /** {@code SecPanelSplitHelper.QS_STATE} / {@code PanelTransitionAnimator.QS_STATE}. */
    public static final int QS_STATE = 0;

    /** {@code SecPanelSplitHelper.SHADE_STATE} / {@code PanelTransitionAnimator.SHADE_STATE}. */
    public static final int SHADE_STATE = 1;

    /** {@code SecPanelSplitHelper.TRANSITION_STATE} / {@code PanelTransitionAnimator.TRANSITION_STATE}. */
    public static final int TRANSITION_STATE = 2;

    /** {@code SecPanelSplitHelper.COMBINED_STATE}. */
    public static final int COMBINED_STATE = 3;

    /** {@code PanelSlideEventHandler.Direction.LEFT} enum name. */
    public static final String DIRECTION_LEFT = "LEFT";

    /** {@code PanelSlideEventHandler.Direction.RIGHT} enum name. */
    public static final String DIRECTION_RIGHT = "RIGHT";

    /** Compose {@code slideFraction} value when Quick Settings is visible/drawable. */
    public static final float COMPOSE_SLIDE_FRACTION_VISIBLE = 0.0f;

    /** Compose {@code slideFraction} value when Quick Settings is hidden in settled Shade state. */
    public static final float COMPOSE_SLIDE_FRACTION_HIDDEN = 1.0f;

    private HorizontalTransitionMath() {
    }

    public static float clamp01(float value) {
        if (Float.isNaN(value) || value <= 0.0f) {
            return 0.0f;
        }
        if (value >= 1.0f) {
            return 1.0f;
        }
        return value;
    }

    /**
     * Returns true when {@code directionName} represents a horizontal panel swipe
     * ({@code LEFT} or {@code RIGHT}).
     */
    public static boolean isHorizontalDirection(String directionName) {
        return DIRECTION_LEFT.equals(directionName) || DIRECTION_RIGHT.equals(directionName);
    }

    /**
     * Determines whether the current horizontal drag is transitioning toward Quick Settings
     * ({@code true}) or toward Notifications ({@code false}).
     *
     * <p>When {@code stateToChange} is explicitly {@link #QS_STATE} ({@code 0}) or
     * {@link #SHADE_STATE} ({@code 1}), {@code stateToChange == QS_STATE} is authoritative.
     * Otherwise falls back to swipe direction and Samsung's reversed panel order flag:
     * in normal order ({@code reversed == false}), swiping {@code LEFT} brings QS in from the right;
     * in reversed order ({@code reversed == true}), swiping {@code RIGHT} brings QS in from the left.
     */
    public static boolean isTransitionToQs(int stateToChange, String directionName, boolean reversed) {
        if (stateToChange == QS_STATE) {
            return true;
        }
        if (stateToChange == SHADE_STATE) {
            return false;
        }
        if (DIRECTION_LEFT.equals(directionName)) {
            return !reversed;
        }
        if (DIRECTION_RIGHT.equals(directionName)) {
            return reversed;
        }
        return false;
    }

    /**
     * Computes the outgoing page opacity for a given incoming-page drag progress in {@code [0, 1]}.
     *
     * <p>Starts fading on the first visible movement ({@code progress > 0f}) and reaches {@code 0f}
     * linearly at {@code progress == FADE_CUTOFF (0.45f)}, remaining at {@code 0f} while rigid
     * translation continues over {@code (0.45f, 1.0f]}.
     */
    public static float outgoingAlpha(float progress) {
        float p = clamp01(progress);
        if (p >= FADE_CUTOFF) {
            return 0.0f;
        }
        return clamp01(1.0f - (p / FADE_CUTOFF));
    }

    /**
     * Computes the incoming page opacity for a given drag progress.
     *
     * <p>The incoming page renders at full opacity ({@code 1.0f}) immediately as it enters the
     * screen so QS tiles, sliders, and media controls (or Notification rows) are completely visible
     * throughout the drag.
     */
    public static float incomingAlpha(float progress) {
        return 1.0f;
    }

    /**
     * Computes the fraction of Quick Settings currently shown on screen in {@code [0, 1]}.
     *
     * @param fraction     native drag progress in {@code [0, 1]} from {@code PanelTransitionAnimator.slide}
     * @param movingToQs   {@code true} when transitioning Notifications -> QS ({@code stateToChange == 0}),
     *                     {@code false} when transitioning QS -> Notifications ({@code stateToChange == 1})
     */
    public static float qsShownFraction(float fraction, boolean movingToQs) {
        float f = clamp01(fraction);
        return movingToQs ? f : (1.0f - f);
    }

    /**
     * Computes the rigid horizontal translation ({@code translationX}) for the Quick Settings page.
     *
     * <p>In normal panel order ({@code reversed == false}), Quick Settings sits to the right of
     * Notifications: {@code translationX = +width * (1 - qsShown)}.
     *<ul>
     *   <li>Swiping left (Notifications -> QS, {@code qsShown: 0 -> 1}): QS moves from {@code +width} to {@code 0}.
     *   <li>Swiping right (QS -> Notifications, {@code qsShown: 1 -> 0}): QS moves from {@code 0} to {@code +width}.
     * </ul>
     * In reversed panel order ({@code reversed == true}), Quick Settings sits to the left of
     * Notifications: {@code translationX = -width * (1 - qsShown)}.
     */
    public static float qsTranslationX(float fraction, float width, boolean movingToQs, boolean reversed) {
        if (width <= 0.0f) {
            return 0.0f;
        }
        float qsShown = qsShownFraction(fraction, movingToQs);
        float offset = width * (1.0f - qsShown);
        return reversed ? -offset : offset;
    }

    /**
     * Computes the rigid horizontal translation ({@code translationX}) for the Notifications page
     * (and its header date/clock and clear/settings button containers).
     *
     * <p>In normal panel order ({@code reversed == false}), Notifications sits to the left of
     * Quick Settings: {@code translationX = -width * qsShown}.
     * <ul>
     *   <li>Swiping left (Notifications -> QS, {@code qsShown: 0 -> 1}): Notifications moves from {@code 0} to {@code -width}.
     *   <li>Swiping right (QS -> Notifications, {@code qsShown: 1 -> 0}): Notifications moves from {@code -width} to {@code 0}.
     * </ul>
     * In reversed panel order ({@code reversed == true}), Notifications sits to the right of
     * Quick Settings: {@code translationX = +width * qsShown}.
     */
    public static float notificationsTranslationX(
            float fraction, float width, boolean movingToQs, boolean reversed) {
        if (width <= 0.0f) {
            return 0.0f;
        }
        float qsShown = qsShownFraction(fraction, movingToQs);
        float offset = width * qsShown;
        return reversed ? offset : -offset;
    }

    /**
     * Computes the Quick Settings page opacity at drag progress {@code fraction}.
     */
    public static float qsAlpha(float fraction, boolean movingToQs) {
        return movingToQs ? incomingAlpha(fraction) : outgoingAlpha(fraction);
    }

    /**
     * Computes the Notifications page opacity at drag progress {@code fraction}.
     */
    public static float notificationsAlpha(float fraction, boolean movingToQs) {
        return movingToQs ? outgoingAlpha(fraction) : incomingAlpha(fraction);
    }

    /**
     * Returns the Compose {@code slideFraction} to apply during or after a transition.
     *
     * <p>In One UI 9's stock Compose pipeline ({@code SecQSPanelComposeAdapter} ->
     * {@code QuickSettingSceneViewModel} -> {@code SecQuickSettingScene}):
     * <ul>
     *   <li>{@code isPresenting} is {@code !collapsed && slideFraction < 1.0f}.
     *   <li>When {@code slideFraction == 1.0f}, {@code expansionWhilePresenting} drops to {@code 0f}
     *       and {@code translationYOfScene} jumps to {@code -5000.0f}, hiding the entire Compose scene.
     *   <li>For {@code 0f < slideFraction < 1f}, {@code getAlphaOfScene} scales alpha by
     *       {@code 1f - slideFraction}, {@code translationXOfScene} adds {@code slideFraction * 300f},
     *       and {@code QuickTileBoxKt} collapses tile state when {@code slideFraction > 0.5f}.
     * </ul>
     * Therefore, whenever a horizontal transition is actively running ({@code activeHorizontalDrag == true}),
     * {@code slideFraction} MUST be pinned to {@code 0.0f} so the Compose scene renders at full
     * fidelity without internal offset/fade while the outer Android view ({@code qsRootView}) carries
     * the rigid page translation and {@link #FADE_CUTOFF} opacity. Once the gesture settles,
     * {@code slideFraction} returns to {@code 0.0f} for {@link #QS_STATE} or {@code 1.0f} for
     * {@link #SHADE_STATE}.
     */
    public static float targetComposeSlideFraction(boolean activeHorizontalDrag, int panelTransitionState) {
        if (activeHorizontalDrag) {
            return COMPOSE_SLIDE_FRACTION_VISIBLE;
        }
        return settledSlideFraction(panelTransitionState);
    }

    /**
     * Returns the settled Compose {@code slideFraction} for a non-transitioning panel state.
     */
    public static float settledSlideFraction(int panelTransitionState) {
        return panelTransitionState == SHADE_STATE
                ? COMPOSE_SLIDE_FRACTION_HIDDEN
                : COMPOSE_SLIDE_FRACTION_VISIBLE;
    }

    /**
     * Immutable snapshot of computed per-frame transforms for both pages and the Compose scene,
     * used by runtime hooks and unit verification.
     */
    public static final class FrameTransform {
        public final boolean activeHorizontal;
        public final boolean movingToQs;
        public final float progress;
        public final float qsShown;
        public final float qsTranslationX;
        public final float notificationsTranslationX;
        public final float qsAlpha;
        public final float notificationsAlpha;
        public final float composeSlideFraction;

        private FrameTransform(
                boolean activeHorizontal,
                boolean movingToQs,
                float progress,
                float qsShown,
                float qsTranslationX,
                float notificationsTranslationX,
                float qsAlpha,
                float notificationsAlpha,
                float composeSlideFraction) {
            this.activeHorizontal = activeHorizontal;
            this.movingToQs = movingToQs;
            this.progress = progress;
            this.qsShown = qsShown;
            this.qsTranslationX = qsTranslationX;
            this.notificationsTranslationX = notificationsTranslationX;
            this.qsAlpha = qsAlpha;
            this.notificationsAlpha = notificationsAlpha;
            this.composeSlideFraction = composeSlideFraction;
        }

        public static FrameTransform forSlide(
                int panelTransitionState,
                float fraction,
                float overSlideX,
                String directionName,
                int stateToChange,
                float width,
                boolean reversed) {
            boolean active = panelTransitionState == TRANSITION_STATE
                    && overSlideX == 0.0f
                    && isHorizontalDirection(directionName)
                    && width > 0.0f;
            if (!active) {
                float settledCompose = settledSlideFraction(panelTransitionState);
                float settledQsAlpha = (panelTransitionState == SHADE_STATE) ? 0.0f : 1.0f;
                float settledNotifAlpha = (panelTransitionState == QS_STATE) ? 0.0f : 1.0f;
                float qsShown = (panelTransitionState == SHADE_STATE) ? 0.0f : 1.0f;
                return new FrameTransform(
                        false,
                        panelTransitionState == QS_STATE,
                        clamp01(fraction),
                        qsShown,
                        0.0f,
                        0.0f,
                        settledQsAlpha,
                        settledNotifAlpha,
                        settledCompose);
            }

            boolean toQs = isTransitionToQs(stateToChange, directionName, reversed);
            float p = clamp01(fraction);
            float qsShown = qsShownFraction(p, toQs);
            float qsTx = qsTranslationX(p, width, toQs, reversed);
            float notifTx = notificationsTranslationX(p, width, toQs, reversed);
            float qAlpha = qsAlpha(p, toQs);
            float nAlpha = notificationsAlpha(p, toQs);
            return new FrameTransform(
                    true,
                    toQs,
                    p,
                    qsShown,
                    qsTx,
                    notifTx,
                    qAlpha,
                    nAlpha,
                    COMPOSE_SLIDE_FRACTION_VISIBLE);
        }
    }
}
