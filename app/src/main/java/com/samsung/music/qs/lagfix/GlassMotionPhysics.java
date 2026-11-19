package com.samsung.music.qs.lagfix;

/**
 * Frame-by-frame dual spring motion reconstructed from the user-supplied
 * GlassUI 2.3.4 SideSlide / SpringStep bytecode, independently implemented.
 *
 * This is NOT a PathInterpolator or a Samsung release-only animation.
 * The follower uses different physical constants while Samsung is reporting
 * an interactive swipe vs. after the user has stopped changing the target.
 *
 * SpringStep divides delta-time into <= 1/240-second integration steps.
 * Spring period / drag coefficients: 0.15/0 (FOLLOW),
 * 0.34/0.15 (SETTLE), 0.24/0.35 (TAIL).
 */
final class GlassMotionPhysics {
    private static final float TWO_PI = 6.283185307179586f;
    private static final float MAX_DT = 0.033333335f;
    private static final float SUBSTEP = 0.0041666669f;
    private static final long FOLLOW_NS = 120_000_000L;

    private float page, velocity, tail, tailVelocity, target;
    private long lastTimeNs, lastTargetNs;
    private boolean started;

    void begin(boolean toQs, long timestampNs) {
        page = toQs ? 0f : 1f;
        tail = page;
        velocity = 0f;
        tailVelocity = 0f;
        target = page;
        lastTimeNs = timestampNs;
        lastTargetNs = timestampNs;
        started = true;
    }

    boolean isStarted() { return started; }
    float page() { return page; }
    float tail() { return tail; }
    float target() { return target; }

    void target(float newTarget, long timestampNs) {
        if (!started) return;
        newTarget = clamp01(newTarget);
        if (Math.abs(newTarget - target) > 0.00001f) lastTargetNs = timestampNs;
        target = newTarget;
    }

    void frame(long timestampNs) {
        if (!started) return;
        float dt = Math.max(0f, Math.min(MAX_DT, (timestampNs - lastTimeNs) / 1_000_000_000f));
        lastTimeNs = timestampNs;
        if (dt == 0f) return;
        boolean following = timestampNs - lastTargetNs < FOLLOW_NS;
        float[] constants = following ? FOLLOW : SETTLE;
        float[] xv = {page, velocity};
        integrate(xv, target, constants[0], constants[1], dt);
        page = xv[0];
        velocity = xv[1];
        xv[0] = tail;
        xv[1] = tailVelocity;
        integrate(xv, page, TAIL[0], TAIL[1], dt);
        tail = xv[0];
        tailVelocity = xv[1];
    }

    boolean isSettled() {
        return started && Math.abs(page-target) < 0.001f
                && Math.abs(velocity) < 0.01f
                && Math.abs(page-tail) < 0.001f
                && Math.abs(tailVelocity) < 0.01f;
    }

    void stop() { started = false; }

    private static float[] spring(float period, float drag) {
        float frequency = TWO_PI / period;
        return new float[] {frequency * frequency, 2f * (1f - drag) * frequency};
    }
    private static final float[] FOLLOW = spring(0.15f, 0f);
    private static final float[] SETTLE = spring(0.34f, 0.15f);
    private static final float[] TAIL = spring(0.24f, 0.35f);

    private static void integrate(float[] xv, float goal, float stiffness, float damping, float dt) {
        // Same bounded semi-implicit Euler update order as GlassUI SpringStep.
        while (dt > 0f) {
            float h = Math.min(dt, SUBSTEP);
            xv[1] += ((goal - xv[0]) * stiffness - xv[1] * damping) * h;
            xv[0] += xv[1] * h;
            dt -= h;
        }
    }

    static float clamp01(float f) {
        if (Float.isNaN(f) || f < 0f) return 0f;
        return Math.min(1f, f);
    }
    static float alpha(float shown) { return clamp01(shown * 1.5f); }
    static float squash(float page, float tail, float width) {
        if (width <= 0f) return 0f;
        return Math.min(Math.abs(page-tail), 0.12f);
    }
}
