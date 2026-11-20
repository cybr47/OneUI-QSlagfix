package com.samsung.music.qs.lagfix;

import org.junit.Test;
import static org.junit.Assert.*;

/** Pure policy tests for the optional offscreen GPU pipeline warm-up. */
public final class ColdGpuWarmupPolicyTest {
    @Test public void neverTouchesGpuWhenScreenIsNotInteractive() {
        assertEquals(-1L, ColdGpuWarmupPolicy.nextDelay(
                900_000L, false, false, 0L, 0L, 0));
        assertEquals(-1L, ColdGpuWarmupPolicy.nextDelay(
                900_000L, false, true, 0L, 0L, 5));
    }

    @Test public void neverWarmsDuringOrJustAfterNativeHorizontalAnimation() {
        assertEquals(3_000L, ColdGpuWarmupPolicy.nextDelay(
                100_000L, true, true, 0L, 0L, 0));
        assertEquals(3_000L, ColdGpuWarmupPolicy.nextDelay(
                100_000L, true, false, 99_990L, 0L, 0));
        assertEquals(0L, ColdGpuWarmupPolicy.nextDelay(
                103_001L, true, false, 99_990L, 0L, 0));
    }

    @Test public void warmsOnceWhenEligibleThenThrottlesRapidRepeats() {
        assertEquals(0L, ColdGpuWarmupPolicy.nextDelay(
                200_000L, true, false, 0L, 0L, 0));
        assertEquals(420_000L, ColdGpuWarmupPolicy.nextDelay(
                230_000L, true, false, 0L, 200_000L, 1));
        assertEquals(0L, ColdGpuWarmupPolicy.nextDelay(
                266_000L, true, false, 0L, 200_000L, 1));
    }

    @Test public void activePanelAlwaysTakesPriorityOverWarmupTimer() {
        assertEquals(3_000L, ColdGpuWarmupPolicy.nextDelay(
                900_000L, true, true, 100_000L, 100_000L, 1));
    }
}
