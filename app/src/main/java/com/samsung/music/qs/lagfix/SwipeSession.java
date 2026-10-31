package com.samsung.music.qs.lagfix;

/** One decision per physical pointer stream; never stores which panel is visible. */
final class SwipeSession {
    enum Decision { WAIT, RELEASE, TOGGLE, CONSUME }
    private final float slop;
    private Decision decision = Decision.WAIT;

    SwipeSession(float slop) {
        this.slop = slop;
    }

    Decision move(float dx, float dy) {
        if (decision == Decision.TOGGLE || decision == Decision.CONSUME) return Decision.CONSUME;
        if (decision == Decision.RELEASE) return Decision.RELEASE;
        float x = Math.abs(dx);
        float y = Math.abs(dy);
        if (Math.max(x, y) <= slop) return Decision.WAIT;
        if (y > x) {
            decision = dy > 0 ? Decision.TOGGLE : Decision.RELEASE;
        } else {
            // Horizontal first: lock this stream, even if the finger later curves downward.
            decision = Decision.CONSUME;
        }
        return decision;
    }
}
