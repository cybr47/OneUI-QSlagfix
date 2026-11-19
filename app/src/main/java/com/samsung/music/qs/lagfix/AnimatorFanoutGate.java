package com.samsung.music.qs.lagfix;

/**
 * Deduplicates repeated native panel-state notifications to Samsung's QS
 * animator-manager fan-out only while ShadeSwitch owns the visual transition.
 *
 * Samsung SystemUI.apk (One UI 9, SHA-256 81a9e975...c8ebebc68b4):
 *  - SecPanelSplitHelper.setDraggedFraction() emits TRANSITION_STATE events
 *    on each fractional swipe update.
 *  - SecQSImplAnimatorManager.onPanelTransitionStateChanged() creates a new
 *    Consumer and executes Stream.filter(...).forEach(...) over the QS animator
 *    list for EVERY such event.
 *  - The manager's children read event.state and/or event.enabled; the only
 *    observed event.fraction access is PanelTransitionAnimator's debug string.
 *  - ShadeSwitch already owns PanelTransitionAnimator's visual horizontal
 *    slide and suppresses its redundant transient-state view changes.
 *
 * Preserve initial notification, enabled changes, final states, and all events
 * when not actively performing a horizontal swipe. This does not change
 * Samsung's touch tracking or any scene property.
 */
final class AnimatorFanoutGate {
    static final int TRANSITION = 2;
    private boolean hasForwardedTransient;
    private boolean lastEnabled;

    void reset() {
        hasForwardedTransient = false;
        lastEnabled = false;
    }

    boolean shouldForward(int state, boolean enabled, boolean ownedHorizontalTransition) {
        if (!ownedHorizontalTransition) {
            return true;
        }
        if (state != TRANSITION) {
            hasForwardedTransient = false;
            return true;
        }
        if (!hasForwardedTransient || lastEnabled != enabled) {
            hasForwardedTransient = true;
            lastEnabled = enabled;
            return true;
        }
        return false;
    }
}
