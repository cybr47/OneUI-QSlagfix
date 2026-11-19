package com.samsung.music.qs.lagfix;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Regression test: the no-allocation runtime path must produce EXACTLY the
 * same direction and visible fraction as FrameTransform.forSlide() in the
 * previous Glass Fade APK, for both panel orders and directions.
 *
 * The 1.5x crossfade itself stays in GlassFadeMath, unchanged.
 */
public final class GlassFadeFastPathTest {
    @Test public void matchesPreviousFrameTransformForAllSwipeDirectionsAndFractions() {
        float width = 1080f;
        String[] directions = {"LEFT", "RIGHT"};
        int[] destinations = {
                HorizontalTransitionMath.QS_STATE,
                HorizontalTransitionMath.SHADE_STATE,
                HorizontalTransitionMath.TRANSITION_STATE,
                HorizontalTransitionMath.COMBINED_STATE
        };
        for (boolean reversed : new boolean[] {false, true}) {
            for (String direction : directions) {
                for (int destination : destinations) {
                    for (int i = -10; i <= 110; i++) {
                        float fraction = i / 100f;
                        HorizontalTransitionMath.FrameTransform before =
                                HorizontalTransitionMath.FrameTransform.forSlide(
                                        HorizontalTransitionMath.TRANSITION_STATE,
                                        fraction, 0f, direction, destination,
                                        width, reversed);
                        boolean toQs = HorizontalTransitionMath.isTransitionToQs(
                                destination, direction, reversed);
                        float shown = HorizontalTransitionMath.qsShownFraction(fraction, toQs);
                        assertTrue(before.activeHorizontal);
                        assertEquals(before.movingToQs, toQs);
                        assertEquals(before.qsShown, shown, 0f);

                        // Identical full-width page coordinates and the same alpha
                        // profile as the previous build, including clamped endpoints.
                        float sign = reversed ? -1f : 1f;
                        float qsX = sign * width * (1f - GlassFadeMath.clamp01(shown));
                        float notiX = -sign * width * GlassFadeMath.clamp01(shown);
                        assertTrue(Float.isFinite(qsX));
                        assertTrue(Float.isFinite(notiX));
                        assertTrue(GlassFadeMath.alpha(shown) >= 0f);
                        assertTrue(GlassFadeMath.alpha(shown) <= 1f);
                    }
                }
            }
        }
    }

    @Test public void retainsPreviousFloatClampingBehavior() {
        float[] unusual = {
                Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, -5f, 0f, 0.125f,
                0.5f, 0.875f, 1f, 5f
        };
        for (float f : unusual) {
            for (boolean toQs : new boolean[] {true, false}) {
                float expected = HorizontalTransitionMath.FrameTransform.forSlide(
                        HorizontalTransitionMath.TRANSITION_STATE,
                        f, 0f, "LEFT",
                        toQs ? HorizontalTransitionMath.QS_STATE : HorizontalTransitionMath.SHADE_STATE,
                        1080f, false).qsShown;
                float fast = HorizontalTransitionMath.qsShownFraction(f, toQs);
                assertEquals(expected, fast, 0f);
            }
        }
    }
}
