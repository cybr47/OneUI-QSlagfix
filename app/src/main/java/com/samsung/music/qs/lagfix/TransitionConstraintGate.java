package com.samsung.music.qs.lagfix;

/**
 * Eliminates duplicate native ConstraintSet rebuild requests only while
 * ShadeSwitch controls the visual horizontal page transition.
 *
 * Verified in Samsung's SystemUI.apk (SHA-256:
 * 81a9e975a9a88a92a94f855c1c4a69dc68dcff28b5e28adfac5adc8ebebc68b4):
 * SecNotificationsQSContainerController$panelTransitionStateListener$1
 * ignores PanelTransitionStateChangeEvent.fraction and posts
 * NotificationsQSContainerController.updateConstraints$1() every time.
 * That routine clones/applies an entire ConstraintSet, but never reads the
 * fractional drag progress. Its other call paths (layout/configuration) and
 * completion-state updates must remain intact.
 *
 * A first TRANSITION_STATE callback is forwarded for every native horizontal
 * session. Later identical transient state callbacks can be skipped.
 * This gate never changes the animation fraction or any View state.
 */
final class TransitionConstraintGate {
    private static final int TRANSITION_STATE = 2;
    private boolean transitionDispatched;

    void start() {
        transitionDispatched = false;
    }

    boolean shouldDispatch(int state, boolean activeHorizontal) {
        if (!activeHorizontal) {
            return true;
        }
        if (state != TRANSITION_STATE) {
            transitionDispatched = false;
            return true;
        }
        if (transitionDispatched) {
            return false;
        }
        transitionDispatched = true;
        return true;
    }
}
