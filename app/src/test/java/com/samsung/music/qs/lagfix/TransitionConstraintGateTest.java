package com.samsung.music.qs.lagfix;

import org.junit.Test;
import static org.junit.Assert.*;

/** Verified event-state contract: QS=0, SHADE=1, TRANSITION=2, COMBINED=3. */
public final class TransitionConstraintGateTest {
    private final TransitionConstraintGate gate = new TransitionConstraintGate();

    @Test public void firstTransitionRunsButFollowingFractionEventsAreCoalesced() {
        gate.start();
        assertTrue(gate.shouldDispatch(2, true));
        for (int i = 0; i < 240; i++) {
            assertFalse("same transition state does not require new constraints",
                    gate.shouldDispatch(2, true));
        }
    }

    @Test public void everyDiscreteCompletionStateAlwaysRuns() {
        gate.start();
        assertTrue(gate.shouldDispatch(2, true));
        assertFalse(gate.shouldDispatch(2, true));
        for (int state : new int[] {0, 1, 3, 0, 1}) {
            assertTrue("never suppress final state", gate.shouldDispatch(state, true));
        }
        assertTrue("a new transition gets its own first constraint update",
                gate.shouldDispatch(2, true));
    }

    @Test public void freshSwipeDoesNotInheritPreviousSwipesSuppression() {
        gate.start();
        assertTrue(gate.shouldDispatch(2, true));
        assertFalse(gate.shouldDispatch(2, true));
        gate.start();
        assertTrue(gate.shouldDispatch(2, true));
    }

    @Test public void whenNotHorizontallySwitchingAllEventsProceed() {
        gate.start();
        for (int i = 0; i < 200; i++) {
            for (int state : new int[] {0, 1, 2, 3}) {
                assertTrue("normal/vertical/lower shade events are untouched",
                        gate.shouldDispatch(state, false));
            }
        }
    }

    @Test public void interruptAndResumeAfterCompletion() {
        gate.start();
        assertTrue(gate.shouldDispatch(2, true));
        assertFalse(gate.shouldDispatch(2, true));
        assertTrue(gate.shouldDispatch(1, true));
        assertTrue(gate.shouldDispatch(2, true));
        assertFalse(gate.shouldDispatch(2, true));
        assertTrue(gate.shouldDispatch(0, true));
    }
}
