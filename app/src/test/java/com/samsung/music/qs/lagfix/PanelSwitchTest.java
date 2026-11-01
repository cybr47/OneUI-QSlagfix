package com.samsung.music.qs.lagfix;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class PanelSwitchTest {
    /** How a fake Samsung reacts to PanelSwitch.Access.animate(). */
    private enum Reaction {
        /** Animation disabled or instantly finished: state is already the destination. */
        COMMIT_NOW,
        /** The normal case: Samsung's first animation frame leaves the panel in TRANSITION. */
        IN_FLIGHT,
        /** Samsung refuses (disabled panel, detail screen...): state does not change. */
        DECLINE
    }

    private static final class SamsungState implements PanelSwitch.Access {
        int state;
        int destination;
        Reaction reaction = Reaction.COMMIT_NOW;
        final List<String> log = new ArrayList<>();
        int finished;
        int animated;
        SamsungState(int state) { this.state = state; }
        @Override public int current() { return state; }
        @Override public int transitionTarget() { return destination; }
        @Override public void finish(int target) {
            assertTrue("must never request combined/closed/transition state", PanelSwitch.terminal(target));
            log.add("finish:" + target);
            finished++;
            state = target;
        }
        @Override public void animate(int target) {
            assertTrue("must never request combined/closed/transition state", PanelSwitch.terminal(target));
            assertTrue("animation may only start from a settled panel", PanelSwitch.terminal(state));
            log.add("animate:" + target);
            animated++;
            switch (reaction) {
                case COMMIT_NOW: state = target; break;
                case IN_FLIGHT: state = PanelSwitch.TRANSITION; destination = target; break;
                case DECLINE: break;
            }
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
            assertEquals(10000, nativeState.animated);
            assertEquals("a settled panel never needs an instant finish", 0, nativeState.finished);
        }
    }

    @Test public void everySwitchIsAnimatedNeverInstant() throws Exception {
        SamsungState nativeState = new SamsungState(PanelSwitch.QS);
        assertTrue(PanelSwitch.toggle(nativeState));
        assertEquals(List.of("animate:" + PanelSwitch.NOTIFICATIONS), nativeState.log);
    }

    @Test public void animationInFlightCountsAsAccepted() throws Exception {
        for (int initial : new int[] {PanelSwitch.QS, PanelSwitch.NOTIFICATIONS}) {
            SamsungState nativeState = new SamsungState(initial);
            nativeState.reaction = Reaction.IN_FLIGHT;
            assertTrue(PanelSwitch.toggle(nativeState));
            assertEquals(PanelSwitch.TRANSITION, nativeState.state);
            assertEquals(initial ^ 1, nativeState.destination);
        }
    }

    @Test public void declinedSwitchIsReportedAndLeavesStateAlone() throws Exception {
        SamsungState nativeState = new SamsungState(PanelSwitch.QS);
        nativeState.reaction = Reaction.DECLINE;
        assertFalse(PanelSwitch.toggle(nativeState));
        assertEquals(PanelSwitch.QS, nativeState.state);
        // A later swipe can still succeed: a refusal is not a permanent condition.
        nativeState.reaction = Reaction.COMMIT_NOW;
        assertTrue(PanelSwitch.toggle(nativeState));
        assertEquals(PanelSwitch.NOTIFICATIONS, nativeState.state);
    }

    @Test public void inFlightTransitionCompletesThenAnimatesFromDestination() throws Exception {
        for (int target : new int[] {PanelSwitch.QS, PanelSwitch.NOTIFICATIONS}) {
            SamsungState nativeState = new SamsungState(PanelSwitch.TRANSITION);
            nativeState.destination = target;
            assertTrue(PanelSwitch.toggle(nativeState));
            assertEquals(List.of("finish:" + target, "animate:" + (target ^ 1)), nativeState.log);
            assertEquals(target ^ 1, nativeState.state);
        }
    }

    @Test public void rapidSwipesDuringTheAnimationNeverDesynchronize() throws Exception {
        SamsungState nativeState = new SamsungState(PanelSwitch.QS);
        nativeState.reaction = Reaction.IN_FLIGHT;
        int expectedDestination = PanelSwitch.NOTIFICATIONS;
        assertTrue(PanelSwitch.toggle(nativeState));
        assertEquals(expectedDestination, nativeState.destination);
        // Every further swipe lands while the previous animation is still running.
        for (int i = 0; i < 1000; i++) {
            assertEquals(PanelSwitch.TRANSITION, nativeState.state);
            assertTrue(PanelSwitch.toggle(nativeState));
            expectedDestination ^= 1;
            assertEquals(expectedDestination, nativeState.destination);
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
            assertTrue(nativeState.log.isEmpty());
        }
        SamsungState nativeState = new SamsungState(PanelSwitch.TRANSITION);
        nativeState.destination = 3;
        assertFalse(PanelSwitch.toggle(nativeState));
        assertTrue(nativeState.log.isEmpty());
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

    @Test public void touchingTheHeaderNeverDisturbsARunningAnimation() throws Exception {
        for (int target : new int[] {PanelSwitch.QS, PanelSwitch.NOTIFICATIONS}) {
            SamsungState nativeState = new SamsungState(PanelSwitch.TRANSITION);
            nativeState.destination = target;
            assertTrue(PanelSwitch.canToggle(nativeState));
            assertEquals("canToggle must be read-only", PanelSwitch.TRANSITION, nativeState.state);
            assertTrue(nativeState.log.isEmpty());
        }
        for (int settled : new int[] {PanelSwitch.QS, PanelSwitch.NOTIFICATIONS}) {
            SamsungState nativeState = new SamsungState(settled);
            assertTrue(PanelSwitch.canToggle(nativeState));
            assertTrue(nativeState.log.isEmpty());
        }
    }

    @Test public void canToggleRejectsStatesThatBelongToSamsung() throws Exception {
        for (int state : new int[] {-1, 3, 99}) {
            assertFalse(PanelSwitch.canToggle(new SamsungState(state)));
        }
        SamsungState nativeState = new SamsungState(PanelSwitch.TRANSITION);
        nativeState.destination = 3;
        assertFalse(PanelSwitch.canToggle(nativeState));
    }
}
