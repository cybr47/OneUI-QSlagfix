package com.samsung.music.qs.lagfix;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class PanelSwitchTest {
    private static final class SamsungState implements PanelSwitch.Access {
        int state;
        int destination;
        final List<Integer> terminalStates = new ArrayList<>();
        SamsungState(int state) { this.state = state; }
        @Override public int current() { return state; }
        @Override public int transitionTarget() { return destination; }
        @Override public void finish(int target) {
            assertTrue("must never request combined/closed/transition state", PanelSwitch.terminal(target));
            terminalStates.add(target);
            state = target;
        }
    }

    @Test public void tenThousandRapidAlternatingSwipesHaveNoLimit() throws Exception {
        for (int initial : new int[] {PanelSwitch.QS, PanelSwitch.NOTIFICATIONS}) {
            SamsungState nativeState = new SamsungState(initial);
            for (int i = 1; i <= 10000; i++) {
                SwipeSession gesture = new SwipeSession(8);
                assertEquals(SwipeSession.Decision.TOGGLE, gesture.move(0, 30));
                assertTrue(PanelSwitch.toggle(nativeState));
                assertEquals(initial ^ (i & 1), nativeState.state);
                assertEquals(SwipeSession.Decision.CONSUME, gesture.move(0, 50));
            }
            assertEquals(10000, nativeState.terminalStates.size());
        }
    }

    @Test public void inFlightTransitionCompletesThenTogglesFromDestination() throws Exception {
        for (int target : new int[] {PanelSwitch.QS, PanelSwitch.NOTIFICATIONS}) {
            SamsungState nativeState = new SamsungState(PanelSwitch.TRANSITION);
            nativeState.destination = target;
            assertTrue(PanelSwitch.toggle(nativeState));
            assertEquals(List.of(target, target ^ 1), nativeState.terminalStates);
            assertEquals(target ^ 1, nativeState.state);
        }
    }

    @Test public void externalStateChangesCannotDesynchronizeALocalToggle() throws Exception {
        SamsungState nativeState = new SamsungState(PanelSwitch.QS);
        Random random = new Random(47);
        for (int i = 0; i < 5000; i++) {
            int visible = random.nextBoolean() ? PanelSwitch.QS : PanelSwitch.NOTIFICATIONS;
            if (random.nextBoolean()) {
                nativeState.state = PanelSwitch.TRANSITION;
                nativeState.destination = visible;
            } else {
                nativeState.state = visible;
            }
            assertTrue(PanelSwitch.toggle(nativeState));
            assertEquals(visible ^ 1, nativeState.state);
        }
    }

    @Test public void unknownCombinedAndInvalidTransitionAreUntouched() throws Exception {
        for (int state : new int[] {-1, 3, 99}) {
            SamsungState nativeState = new SamsungState(state);
            assertFalse(PanelSwitch.toggle(nativeState));
            assertTrue(nativeState.terminalStates.isEmpty());
        }
        SamsungState nativeState = new SamsungState(PanelSwitch.TRANSITION);
        nativeState.destination = 3;
        assertFalse(PanelSwitch.toggle(nativeState));
        assertTrue(nativeState.terminalStates.isEmpty());
    }

    @Test public void eachGestureReadsStateAgainAfterAnotherActorChangesIt() throws Exception {
        SamsungState nativeState = new SamsungState(PanelSwitch.QS);
        assertTrue(PanelSwitch.toggle(nativeState));
        assertEquals(PanelSwitch.NOTIFICATIONS, nativeState.state);
        // The user closes and reopens the opposite side, or an accessibility action changes panels.
        nativeState.state = PanelSwitch.QS;
        assertTrue(PanelSwitch.toggle(nativeState));
        assertEquals(PanelSwitch.NOTIFICATIONS, nativeState.state);
    }
}
