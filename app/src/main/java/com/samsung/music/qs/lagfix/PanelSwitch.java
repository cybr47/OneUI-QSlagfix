package com.samsung.music.qs.lagfix;

/** Reads Samsung's state on every operation; no alternating local boolean or animation queue. */
final class PanelSwitch {
    static final int QS = 0;
    static final int NOTIFICATIONS = 1;
    static final int TRANSITION = 2;

    interface Access {
        int current() throws ReflectiveOperationException;
        int transitionTarget() throws ReflectiveOperationException;
        /** Instantly completes a switch with no animation. Only used to settle an in-flight transition. */
        void finish(int target) throws ReflectiveOperationException;
        /** Starts Samsung's own animated switch (fade out to the bottom, fade in from the top). */
        void animate(int target) throws ReflectiveOperationException;
    }

    static boolean terminal(int state) {
        return state == QS || state == NOTIFICATIONS;
    }

    /** Snaps any in-flight transition to its destination so the next switch starts from a clean state. */
    static boolean settle(Access access) throws ReflectiveOperationException {
        if (access.current() == TRANSITION) {
            int target = access.transitionTarget();
            if (!terminal(target)) return false;
            access.finish(target);
        }
        return terminal(access.current());
    }

    /**
     * Read-only: would {@link #toggle} be able to act right now? Unlike {@link #settle} this never touches
     * Samsung's state, so merely touching the header cannot snap a running panel animation to its end.
     */
    static boolean canToggle(Access access) throws ReflectiveOperationException {
        int state = access.current();
        return state == TRANSITION ? terminal(access.transitionTarget()) : terminal(state);
    }

    /**
     * Starts one animated switch to the other panel. Returns true when Samsung accepted it: the panel is
     * either mid-animation (TRANSITION) or already at the destination (animations disabled). Returns false
     * when Samsung declined (state unchanged); callers must treat that as "ignore this swipe", not as a
     * reason to disable the feature.
     */
    static boolean toggle(Access access) throws ReflectiveOperationException {
        if (!settle(access)) return false;
        int target = access.current() == QS ? NOTIFICATIONS : QS;
        access.animate(target);
        int state = access.current();
        return state == TRANSITION || state == target;
    }

    private PanelSwitch() {}
}
