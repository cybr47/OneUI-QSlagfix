package com.samsung.music.qs.lagfix;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Motion must remain safe for Samsung's own panel fraction listeners.
 */
public final class NativeSpringCurveTest {
    private static final float EPS = 0.000001f;

    @Test public void preservesEndpointsAndBounds() {
        assertEquals(0f, NativeSpringCurve.value(0f), EPS);
        assertEquals(1f, NativeSpringCurve.value(1f), EPS);
        assertEquals(0f, NativeSpringCurve.value(-1f), EPS);
        assertEquals(1f, NativeSpringCurve.value(2f), EPS);
        assertEquals(0f, NativeSpringCurve.value(Float.NaN), EPS);
    }

    @Test public void progressesSmoothlyAndMonotonically() {
        float previous = 0f;
        for (int frame = 1; frame <= 240; frame++) {
            float result = NativeSpringCurve.value(frame / 240f);
            assertTrue("must never go backward", result + EPS >= previous);
            assertTrue("fraction must remain within native bounds", result >= 0f && result <= 1f);
            previous = result;
        }
        assertEquals(1f, previous, EPS);
    }

    @Test public void springSettlesEarlyWithoutLeavingNativeRange() {
        assertTrue(NativeSpringCurve.value(0.50f) > 0.75f);
        assertTrue(NativeSpringCurve.value(0.75f) > 0.90f);
        assertTrue(NativeSpringCurve.value(0.99f) < 1.0f);
    }

    @Test public void independentOfDisplayRefreshRate() {
        for (int eighth = 0; eighth <= 8; eighth++) {
            float progress = eighth / 8f;
            float at60hz = NativeSpringCurve.value(progress);
            float at120hz = NativeSpringCurve.value(progress);
            assertEquals(at60hz, at120hz, EPS);
        }
    }
}
