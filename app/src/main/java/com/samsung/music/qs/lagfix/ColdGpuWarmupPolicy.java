package com.samsung.music.qs.lagfix;

/**
 * Pure scheduling policy for the optional offscreen shader cache warm-up.
 * Values are uptime milliseconds; return -1 when display is off,
 * 0 when safe to warm, or a positive retry delay.
 */
final class ColdGpuWarmupPolicy {
    static final long INTERVAL_MS = 7 * 60_000L;
    static final long MIN_REPEAT_MS = 65_000L;
    static final long RECENT_PANEL_MS = 3_000L;

    private ColdGpuWarmupPolicy() {}

    static long nextDelay(
            long nowMs, boolean interactive, boolean horizontalActive,
            long lastPanelTouchMs, long lastWarmMs, int completedWarmups) {
        if (!interactive) return -1L;
        if (horizontalActive || nowMs - lastPanelTouchMs < RECENT_PANEL_MS) {
            return RECENT_PANEL_MS;
        }
        if (completedWarmups > 0 && nowMs - lastWarmMs < MIN_REPEAT_MS) {
            return INTERVAL_MS;
        }
        return 0L;
    }
}
