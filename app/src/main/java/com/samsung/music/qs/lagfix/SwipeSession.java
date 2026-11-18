package com.samsung.music.qs.lagfix;

/** One decision for the physical touch, even if multiple Samsung views initialize the slide. */
final class SwipeSession {
    enum Start { NONE, HEADER, NOTIFICATIONS }
    enum Pull { WAIT, DOWN, PASS }

    final Start start;
    final long downTime;
    final int deviceId;
    final float rawX, rawY;
    private final float slop;
    private boolean horizontal;
    private boolean passedBody;
    private boolean nativeInitialized;
    private float nativeX, nativeY;

    SwipeSession(float slop, Start start, long downTime, int deviceId, float rawX, float rawY) {
        this.slop = slop;
        this.start = start;
        this.downTime = downTime;
        this.deviceId = deviceId;
        this.rawX = rawX;
        this.rawY = rawY;
    }

    boolean sameTouch(long time, int device) {
        return downTime == time && deviceId == device;
    }

    void recordNativeDown(float x, float y) {
        nativeInitialized = true;
        nativeX = x;
        nativeY = y;
    }

    void invalidateNativeOrigin() {
        nativeInitialized = false;
    }

    boolean initializedAt(float x, float y) {
        return nativeInitialized && nativeX == x && nativeY == y;
    }

    boolean allowsVertical() {
        return start != Start.NONE && !horizontal && !passedBody;
    }

    Pull notificationPull(float dx, float dy, int pointers) {
        if (start != Start.NOTIFICATIONS || !allowsVertical()) return Pull.PASS;
        if (pointers == 1) {
            float ax = Math.abs(dx);
            if (ax <= slop && Math.abs(dy) <= slop) return Pull.WAIT;
            if (dy > slop && dy > ax) return Pull.DOWN;
        }
        // Let a scroll, a seek or a second finger keep the entire gesture, including reversals.
        passedBody = true;
        return Pull.PASS;
    }

    boolean blockDirection(float dx, float dy, boolean nativeDown) {
        if (nativeDown) return false;
        if (horizontal) return true;
        horizontal = Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy);
        return horizontal;
    }
}
