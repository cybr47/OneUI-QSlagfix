package com.samsung.music.qs.lagfix;

/**
 * Pure gate for read-only native cold-session setup. This is deliberately
 * conservative: never force Kotlin Lazy creation, never prebind during
 * Samsung's transient state, and never mutate an active slide's view graph.
 */
final class NativeColdInitPolicy {
    private NativeColdInitPolicy() {}

    static boolean canPrebind(
            int nativeState, boolean splitHelperInitialized, boolean anyHorizontalActive) {
        return !anyHorizontalActive && splitHelperInitialized
                && (nativeState == HorizontalTransitionMath.QS_STATE
                    || nativeState == HorizontalTransitionMath.SHADE_STATE);
    }
}
