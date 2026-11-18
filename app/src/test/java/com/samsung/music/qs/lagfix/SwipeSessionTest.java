package com.samsung.music.qs.lagfix;

import org.junit.Test;
import static org.junit.Assert.*;

public class SwipeSessionTest {
    private static SwipeSession start(SwipeSession.Start area) {
        return new SwipeSession(8, area, 100L, 1, 200f, 400f);
    }

    @Test public void notificationBodyNeedsADominantDownPullBeyondStockSlop() {
        SwipeSession s = start(SwipeSession.Start.NOTIFICATIONS);
        assertEquals(SwipeSession.Pull.WAIT, s.notificationPull(1, 8, 1));
        assertEquals(SwipeSession.Pull.DOWN, s.notificationPull(9, 15, 1));
        assertTrue(s.allowsVertical());
        assertEquals(SwipeSession.Pull.PASS, start(SwipeSession.Start.NONE).notificationPull(0, 50, 1));
        assertEquals(SwipeSession.Pull.PASS, start(SwipeSession.Start.HEADER).notificationPull(0, 50, 1));
    }

    @Test public void aSeekScrollOrSecondPointerKeepsItsStreamEvenIfItTurnsDown() {
        for (int[] move : new int[][] {{28, 3, 1}, {-28, 8, 1}, {2, -20, 1}, {0, 0, 2}}) {
            SwipeSession s = start(SwipeSession.Start.NOTIFICATIONS);
            assertEquals(SwipeSession.Pull.PASS, s.notificationPull(move[0], move[1], move[2]));
            assertEquals(SwipeSession.Pull.PASS, s.notificationPull(0, 100, 1));
            assertFalse(s.allowsVertical());
        }
    }

    @Test public void onlyTheSameTouchAndCoordinateSpaceCanReuseTheNativeOrigin() {
        SwipeSession s = start(SwipeSession.Start.NOTIFICATIONS);
        assertTrue(s.sameTouch(100L, 1));
        assertFalse(s.sameTouch(101L, 1));
        assertFalse(s.sameTouch(100L, 2));
        assertFalse(s.initializedAt(200, 400));
        s.recordNativeDown(200, 350);
        assertFalse(s.initializedAt(200, 400));
        assertTrue(s.initializedAt(200, 350));
        s.invalidateNativeOrigin();
        assertFalse(s.initializedAt(200, 350));
    }

    @Test public void downwardMovementNeverCreatesAnAutomaticSwitchDecision() {
        SwipeSession gesture = start(SwipeSession.Start.HEADER);
        for (float y : new float[] {0, 7, 12, 180, 90, 0, -20}) {
            assertFalse(gesture.blockDirection(0, y, false));
            assertTrue(gesture.allowsVertical());
        }
    }

    @Test public void nativeDownDragKeepsControlWhileReversingOrMovingDiagonally() {
        SwipeSession gesture = start(SwipeSession.Start.HEADER);
        for (float y : new float[] {200, 110, 50, 0, -30}) {
            assertFalse(gesture.blockDirection(500, y, true));
        }
        assertTrue(gesture.allowsVertical());
    }

    @Test public void horizontalFirstCannotTurnIntoAPanelSwitchWhenCurvedDown() {
        for (int sign : new int[] {-1, 1}) {
            SwipeSession gesture = start(SwipeSession.Start.HEADER);
            assertTrue(gesture.blockDirection(sign * 20, 3, false));
            assertTrue(gesture.blockDirection(sign * 21, 250, false));
            assertFalse(gesture.allowsVertical());
        }
    }

    @Test public void tapsAndSubSlopMotionDoNotBlockNativeDirectionOrHeader() {
        SwipeSession gesture = start(SwipeSession.Start.HEADER);
        assertFalse(gesture.blockDirection(8, 0, false));
        assertFalse(gesture.blockDirection(-7, 2, false));
        assertTrue(gesture.allowsVertical());
    }

    @Test public void upwardCloseIsLeftToSamsung() {
        SwipeSession gesture = start(SwipeSession.Start.HEADER);
        assertFalse(gesture.blockDirection(4, -30, false));
        assertTrue(gesture.allowsVertical());
    }

    @Test public void nonHeaderStreamsNeverGainExpandedHeaderEligibility() {
        SwipeSession gesture = start(SwipeSession.Start.NONE);
        assertFalse(gesture.blockDirection(0, 100, false));
        assertFalse(gesture.allowsVertical());
        assertTrue(gesture.blockDirection(200, 100, false));
    }

    @Test public void aNewPhysicalGestureDoesNotInheritHorizontalRejection() {
        SwipeSession old = start(SwipeSession.Start.HEADER);
        assertTrue(old.blockDirection(30, 0, false));
        SwipeSession next = start(SwipeSession.Start.HEADER);
        assertFalse(next.blockDirection(0, 30, false));
        assertTrue(next.allowsVertical());
    }
}
