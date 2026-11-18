package com.samsung.music.qs.lagfix;

import org.junit.Test;
import static org.junit.Assert.*;

public class SwipeSessionTest {
    @Test public void downwardMovementNeverCreatesAnAutomaticSwitchDecision() {
        SwipeSession gesture = new SwipeSession(8, true);
        for (float y : new float[] {0, 7, 12, 180, 90, 0, -20}) {
            assertFalse(gesture.blockDirection(0, y, false));
            assertTrue(gesture.allowHeaderGate());
        }
    }

    @Test public void nativeDownDragKeepsControlWhileReversingOrMovingDiagonally() {
        SwipeSession gesture = new SwipeSession(8, true);
        for (float y : new float[] {200, 110, 50, 0, -30}) {
            assertFalse(gesture.blockDirection(500, y, true));
        }
        assertTrue(gesture.allowHeaderGate());
    }

    @Test public void horizontalFirstCannotTurnIntoAPanelSwitchWhenCurvedDown() {
        for (int sign : new int[] {-1, 1}) {
            SwipeSession gesture = new SwipeSession(8, true);
            assertTrue(gesture.blockDirection(sign * 20, 3, false));
            assertTrue(gesture.blockDirection(sign * 21, 250, false));
            assertFalse(gesture.allowHeaderGate());
        }
    }

    @Test public void tapsAndSubSlopMotionDoNotBlockNativeDirectionOrHeader() {
        SwipeSession gesture = new SwipeSession(8, true);
        assertFalse(gesture.blockDirection(8, 0, false));
        assertFalse(gesture.blockDirection(-7, 2, false));
        assertTrue(gesture.allowHeaderGate());
    }

    @Test public void upwardCloseIsLeftToSamsung() {
        SwipeSession gesture = new SwipeSession(8, true);
        assertFalse(gesture.blockDirection(4, -30, false));
        assertTrue(gesture.allowHeaderGate());
    }

    @Test public void nonHeaderStreamsNeverGainExpandedHeaderEligibility() {
        SwipeSession gesture = new SwipeSession(8, false);
        assertFalse(gesture.blockDirection(0, 100, false));
        assertFalse(gesture.allowHeaderGate());
        assertTrue(gesture.blockDirection(200, 100, false));
    }

    @Test public void aNewPhysicalGestureDoesNotInheritHorizontalRejection() {
        SwipeSession old = new SwipeSession(8, true);
        assertTrue(old.blockDirection(30, 0, false));
        SwipeSession next = new SwipeSession(8, true);
        assertFalse(next.blockDirection(0, 30, false));
        assertTrue(next.allowHeaderGate());
    }
}
