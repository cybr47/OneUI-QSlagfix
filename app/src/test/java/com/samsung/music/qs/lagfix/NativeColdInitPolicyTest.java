package com.samsung.music.qs.lagfix;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeColdInitPolicyTest {
    @Test public void onlySettledNativeQsAndShadeAreEligible() {
        assertTrue(NativeColdInitPolicy.canPrebind(0, true, false));
        assertTrue(NativeColdInitPolicy.canPrebind(1, true, false));
        assertFalse(NativeColdInitPolicy.canPrebind(2, true, false));
        assertFalse(NativeColdInitPolicy.canPrebind(3, true, false));
        assertFalse(NativeColdInitPolicy.canPrebind(-1, true, false));
    }

    @Test public void neverForcesKotlinLazyInitialization() {
        for (int state : new int[] {0, 1, 2, 3}) {
            assertFalse(NativeColdInitPolicy.canPrebind(state, false, false));
        }
    }

    @Test public void neverTouchesActiveHorizontalTransitionViewGraph() {
        for (int state : new int[] {0, 1, 2, 3}) {
            assertFalse(NativeColdInitPolicy.canPrebind(state, true, true));
        }
    }
}
