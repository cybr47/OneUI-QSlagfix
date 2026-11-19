package com.samsung.music.qs.lagfix;

import org.junit.Test;
import static org.junit.Assert.*;

/** Checks the unchanged GlassUI-style fade profile and strict bounds. */
public final class GlassFadeMathTest {
    @Test public void preservesFadeProfile() {
        assertEquals(0f, GlassFadeMath.alpha(0f), 0f);
        assertEquals(0.375f, GlassFadeMath.alpha(0.25f), 0.0001f);
        assertEquals(0.75f, GlassFadeMath.alpha(0.50f), 0.0001f);
        assertEquals(1f, GlassFadeMath.alpha(2f / 3f), 0.0001f);
        assertEquals(1f, GlassFadeMath.alpha(1f), 0f);
    }
    @Test public void isMonotonicAndHasNoOvershoot() {
        float prev = 0f;
        for (int i = 0; i <= 240; i++) {
            float a = GlassFadeMath.alpha(i / 240f);
            assertTrue(a >= prev);
            assertTrue(a >= 0f && a <= 1f);
            prev = a;
        }
    }
    @Test public void rejectsInvalidFractionsSafely() {
        assertEquals(0f, GlassFadeMath.alpha(Float.NaN), 0f);
        assertEquals(0f, GlassFadeMath.alpha(-2f), 0f);
        assertEquals(1f, GlassFadeMath.alpha(4f), 0f);
    }
}
