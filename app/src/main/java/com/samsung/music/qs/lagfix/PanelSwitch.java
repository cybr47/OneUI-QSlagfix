package com.samsung.music.qs.lagfix;

/** Reads Samsung's state on every operation; no alternating local boolean or animation queue. */
final class PanelSwitch {
    static final int QS = 0;
    static final int NOTIFICATIONS = 1;
    static final int TRANSITION = 2;

    interface Access {
        int current() throws ReflectiveOperationException;
        int transitionTarget() throws ReflectiveOperationException;
        void finish(int target) throws ReflectiveOperationException;
    }

    static boolean terminal(int state) {
        return state == QS || state == NOTIFICATIONS;
    }

    static boolean settle(Access access) throws ReflectiveOperationException {
        if (access.current() == TRANSITION) {
            int target = access.transitionTarget();
            if (!terminal(target)) return false;
            access.finish(target);
        }
        return terminal(access.current());
    }

    static boolean toggle(Access access) throws ReflectiveOperationException {
        if (!settle(access)) return false;
        int target = access.current() == QS ? NOTIFICATIONS : QS;
        access.finish(target);
        return access.current() == target;
    }

    private PanelSwitch() {}
}
