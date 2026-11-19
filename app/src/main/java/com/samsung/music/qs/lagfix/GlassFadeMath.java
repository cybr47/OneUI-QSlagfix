package com.samsung.music.qs.lagfix;

/**
 * GlassUI-style crossfade, independent of movement timing.
 * Samsung's native horizontal transition fraction controls both movement
 * and fading, with no separate velocity/inertia state.
 */
final class GlassFadeMath {
    private GlassFadeMath() {}

    static float clamp01(float value) {
        if (Float.isNaN(value) || value <= 0f) return 0f;
        if (value >= 1f) return 1f;
        return value;
    }

    // Retain the previously tested fade profile exactly:
    // incoming page reaches full opacity when ~2/3 visible.
    static float alpha(float shown) {
        return clamp01(shown * 1.5f);
    }
}
