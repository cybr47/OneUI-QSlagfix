package com.samsung.music.qs.lagfix;

/**
 * Pure math and state-calculation helpers for the GlassUI-style horizontal transition between
 * Notifications and Quick Settings on One UI 9.
 *
 * <p>Outgoing opacity curve ({@link #FADE_CUTOFF} = {@code 0.50f}):
 * <pre>
 * t = clamp01(progress / FADE_CUTOFF)
 * alpha = (1 - t * t) * (1 - t * t * t)
 * </pre>
 * This begins fading on the first visible movement, eases smoothly through {@code 0.65625} at
 * {@code 25%} drag and {@code 0.17568} at {@code 40%} drag, and reaches {@code 0.0f} when the
 * incoming panel is {@code 50%} of the way in (and stays {@code 0.0f} beyond {@code 50%}) in both
 * swipe directions. Holding the finger still holds both translation and opacity, and reversing
 * restores opacity along the exact same curve.
 */
public final class HorizontalTransitionMath {
    /**
     * Named incoming-page progress cutoff ({@code 0.50f}) at which the outgoing page eases to
     * {@code 0.0f} opacity in both directions ({@code Notifications -> QS} and
     * {@code QS -> Notifications}).
     */
    public static final float FADE_CUTOFF = 0.50f;

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

    /** Blur alpha passed to child {@code ActivatableNotificationView}s during a horizontal drag. */
    public static final float NESTED_NOTIFICATION_BLUR_ALPHA_ACTIVE = 1.0f;

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
     * Computes the outgoing page opacity for a given incoming-page drag progress in {@code [0, 1]}
     * using the eased quintic curve over {@link #FADE_CUTOFF} ({@code 0.50f}):
     * <pre>
     * t = clamp01(progress / FADE_CUTOFF)
     * alpha = (1 - t * t) * (1 - t * t * t)
     * </pre>
     */
    public static float outgoingAlpha(float progress) {
        float p = clamp01(progress);
        if (p >= FADE_CUTOFF) {
            return 0.0f;
        }
        float t = clamp01(p / FADE_CUTOFF);
        float tt = t * t;
        return clamp01((1.0f - tt) * (1.0f - tt * t));
    }

    /**
     * Computes the incoming page opacity for a given drag progress.
     *
     * <p>The incoming page renders at full opacity ({@code 1.0f}) immediately from the first frame
     * so QS tiles, sliders, and media controls (or Notification rows) are completely visible
     * throughout the drag.
     */
    public static float incomingAlpha(float progress) {
        return 1.0f;
    }

    /**
     * Returns the blur alpha to pass to nested {@code ActivatableNotificationView.setBlurAlphaFromParent(float)}
     * calls inside {@code NotificationStackScrollLayout.setAlpha(float)}.
     *
     * <p>In One UI 9, {@code NotificationStackScrollLayout.setAlpha(v)} sets both the parent
     * {@code ViewGroup} hardware-layer alpha ({@code super.setAlpha(v)}) and loops over every child
     * {@code ActivatableNotificationView} to call {@code setBlurAlphaFromParent(v)}. If both run during
     * a horizontal fade, each notification card's blur background opacity is multiplied by {@code v}
     * a second time ({@code v * v}), making outgoing notifications lose opacity prematurely. Pinning
     * child blur alpha to {@code 1.0f} while a horizontal swipe is active lets the parent container's
     * single eased opacity curve govern the entire Notifications panel uniformly.
     */
    public static float effectiveNestedNotificationBlurAlpha(
            boolean activeHorizontalSwipe, float requestedAlpha) {
        if (activeHorizontalSwipe) {
            return NESTED_NOTIFICATION_BLUR_ALPHA_ACTIVE;
        }
        return clamp01(requestedAlpha);
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
     * <ul>
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
     * the rigid page translation and eased {@link #FADE_CUTOFF} opacity. Once the gesture settles,
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
