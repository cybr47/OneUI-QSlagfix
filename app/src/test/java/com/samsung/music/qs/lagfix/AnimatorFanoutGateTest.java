package com.samsung.music.qs.lagfix;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Regression: no visible Samsung state/enable transition may be dropped;
 * fraction-only duplicates are coalesced only inside owned horizontal swipes.
 */
public final class AnimatorFanoutGateTest {
    @Test public void firstTransientRunsThenRepeatedUpdatesAreSkipped() {
        AnimatorFanoutGate gate = new AnimatorFanoutGate();
        gate.reset();
        assertTrue(gate.shouldForward(2, true, true));
        for (int i = 0; i < 500; i++) {
            assertFalse(gate.shouldForward(2, true, true));
        }
    }

    @Test public void enabledChangesAreAlwaysForwarded() {
        AnimatorFanoutGate gate = new AnimatorFanoutGate();
        gate.reset();
        assertTrue(gate.shouldForward(2, true, true));
        assertFalse(gate.shouldForward(2, true, true));
        assertTrue(gate.shouldForward(2, false, true));
        assertFalse(gate.shouldForward(2, false, true));
        assertTrue(gate.shouldForward(2, true, true));
    }

    @Test public void finalStateAlwaysFlowsAndRearmsNextTransition() {
        AnimatorFanoutGate gate = new AnimatorFanoutGate();
        gate.reset();
        assertTrue(gate.shouldForward(2, true, true));
        assertFalse(gate.shouldForward(2, true, true));
        for (int state : new int[] {0, 1, 3, 0, 1}) {
            assertTrue("final state is mandatory", gate.shouldForward(state, true, true));
        }
        assertTrue(gate.shouldForward(2, true, true));
    }

    @Test public void nonHorizontalAndInitialPanelEventsNeverDrop() {
        AnimatorFanoutGate gate = new AnimatorFanoutGate();
        gate.reset();
        for (int i = 0; i < 300; i++) {
            for (int state : new int[] {0, 1, 2, 3}) {
                assertTrue(gate.shouldForward(state, i % 2 == 0, false));
            }
        }
    }

    @Test public void resetPreventsStateLeakBetweenSessions() {
        AnimatorFanoutGate gate = new AnimatorFanoutGate();
        gate.reset();
        assertTrue(gate.shouldForward(2, true, true));
        assertFalse(gate.shouldForward(2, true, true));
        gate.reset();
        assertTrue(gate.shouldForward(2, true, true));
    }
}
