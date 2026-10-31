package com.samsung.music.qs.lagfix;

import org.junit.Test;
import static org.junit.Assert.*;

public class SwipeSessionTest {
    @Test public void downwardStreamCommitsExactlyOnce() {
        SwipeSession gesture = new SwipeSession(8);
        assertEquals(SwipeSession.Decision.WAIT, gesture.move(1, 7));
        assertEquals(SwipeSession.Decision.TOGGLE, gesture.move(2, 12));
        for (int i = 0; i < 1000; i++) {
            assertEquals(SwipeSession.Decision.CONSUME, gesture.move(i % 11, 12 + i));
        }
    }

    @Test public void horizontalCannotBecomeToggleWhenFingerCurvesDown() {
        for (int sign : new int[] {-1, 1}) {
            SwipeSession gesture = new SwipeSession(8);
            assertEquals(SwipeSession.Decision.CONSUME, gesture.move(sign * 20, 3));
            assertEquals(SwipeSession.Decision.CONSUME, gesture.move(sign * 21, 150));
        }
    }

    @Test public void upwardCloseAndDiagonalUpReturnToStock() {
        for (float x : new float[] {-4, 0, 4}) {
            SwipeSession gesture = new SwipeSession(8);
            assertEquals(SwipeSession.Decision.RELEASE, gesture.move(x, -20));
            assertEquals(SwipeSession.Decision.RELEASE, gesture.move(x, 60));
        }
    }

    @Test public void belowSlopIsNotASwipe() {
        SwipeSession gesture = new SwipeSession(8);
        assertEquals(SwipeSession.Decision.WAIT, gesture.move(8, 8));
        assertEquals(SwipeSession.Decision.WAIT, gesture.move(-7, 2));
    }

    @Test public void twoDistinctGesturesCanEachCommit() {
        assertEquals(SwipeSession.Decision.TOGGLE, new SwipeSession(8).move(0, 20));
        assertEquals(SwipeSession.Decision.TOGGLE, new SwipeSession(8).move(0, 20));
    }
}
