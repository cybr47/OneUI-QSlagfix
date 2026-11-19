package com.samsung.music.qs.lagfix;

/**
 * Damped spring step response used only for the native horizontal release animation.
 *
 * A normalized critically damped step has no overshoot, so Samsung's panel
 * fraction always remains in [0, 1]. This is an independent implementation
 * inspired by the spring settling observed in GlassUI; no GlassUI code is
 * embedded or redistributed.
 *
 * The native ValueAnimator still supplies VSYNC-aligned frame timestamps,
 * updates Samsung's own slide listeners and calls its own completion callbacks.
 */
final class NativeSpringCurve {
    private static final double OMEGA = 6.0;
    private static final double NORMALIZER =
            1.0 - (1.0 + OMEGA) * Math.exp(-OMEGA);

    private NativeSpringCurve() {}

    static float value(float progress) {
        if (Float.isNaN(progress) || progress <= 0f) return 0f;
        if (progress >= 1f) return 1f;
        double elapsed = OMEGA * progress;
        double position = (1.0 - (1.0 + elapsed) * Math.exp(-elapsed)) / NORMALIZER;
        return (float) Math.max(0.0, Math.min(1.0, position));
    }
}
