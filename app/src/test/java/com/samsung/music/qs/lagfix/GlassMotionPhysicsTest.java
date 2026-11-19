package com.samsung.music.qs.lagfix;

import org.junit.Test;
import static org.junit.Assert.*;

/** Regression checks for the GlassUI-derived timestep/follow/settle/tail behavior. */
public final class GlassMotionPhysicsTest {
    @Test public void physicalMovementTracksSwipeWithoutLeavingNativeFractionRange() {
        GlassMotionPhysics motion = new GlassMotionPhysics();
        long t = 1_000_000_000L;
        motion.begin(true, t);
        assertEquals(0f, motion.page(), 0.0001f);
        motion.target(0.70f, t);
        for (int i = 1; i <= 45; i++) {
            t += 8_333_333L;
            motion.frame(t);
            assertFalse(Float.isNaN(motion.page()));
            assertFalse(Float.isNaN(motion.tail()));
        }
        assertTrue("follows 70%-progress target", motion.page() > 0.62f);
        assertTrue("target is not forgotten", Math.abs(motion.target()-0.70f) < 0.001f);
        motion.target(0.15f, t);
        for (int i = 1; i <= 45; i++) {
            t += 8_333_333L;
            motion.frame(t);
        }
        assertTrue("reversal works", motion.page() < 0.35f);
    }

    @Test public void settlesWithNative120HzFrameTimestampsAndTail() {
        GlassMotionPhysics motion = new GlassMotionPhysics();
        long t = 1_000_000_000L;
        motion.begin(true, t);
        motion.target(1f, t);
        boolean sawLag = false;
        for (int i = 0; i < 300; i++) {
            t += 8_333_333L;
            motion.frame(t);
            if (Math.abs(motion.page()-motion.tail()) > 0.004f) sawLag = true;
        }
        assertTrue("secondary tail spring should lag the lead", sawLag);
        assertEquals(1f, motion.page(), 0.001f);
        assertEquals(1f, motion.tail(), 0.001f);
        assertTrue("animation should settle", motion.isSettled());
    }

    @Test public void supportsReverseDirectionAndClampsInvalidTargets() {
        GlassMotionPhysics motion = new GlassMotionPhysics();
        long t = 500_000_000L;
        motion.begin(false, t);
        assertEquals(1f, motion.page(), 0.0001f);
        motion.target(Float.NaN, t);
        assertEquals(0f, motion.target(), 0f);
        for (int i = 0; i < 260; i++) {
            t += 8_333_333L;
            motion.frame(t);
        }
        assertEquals(0f, motion.page(), 0.001f);
        assertEquals(0f, motion.tail(), 0.001f);
    }

    @Test public void faithfullyMapsGlassUISheetOpacityAndStretchLimit() {
        assertEquals(0f, GlassMotionPhysics.alpha(0f), 0f);
        assertEquals(0.75f, GlassMotionPhysics.alpha(0.5f), 0.00001f);
        assertEquals(1f, GlassMotionPhysics.alpha(1f), 0f);
        assertEquals(0.12f, GlassMotionPhysics.squash(0.8f,0.1f,1080f),0.00001f);
        assertEquals(0.02f, GlassMotionPhysics.squash(0.8f,0.78f,1080f),0.00001f);
    }
}
