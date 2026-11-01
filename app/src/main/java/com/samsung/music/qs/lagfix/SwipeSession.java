package com.samsung.music.qs.lagfix;

/** Native vertical drags remain native; horizontal-first panel switches stay disabled for this stream. */
final class SwipeSession {
    final boolean sharedHeader;
    private final float slop;
    private boolean horizontal;

    SwipeSession(float slop, boolean sharedHeader) {
        this.slop = slop;
        this.sharedHeader = sharedHeader;
    }

    boolean allowHeaderGate() {
        return sharedHeader && !horizontal;
    }

    boolean blockDirection(float dx, float dy, boolean nativeDown) {
        // Samsung locks an established DOWN drag, including reversal and diagonal finger movement.
        if (nativeDown) return false;
        if (horizontal) return true;
        horizontal = Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy);
        return horizontal;
    }
}
