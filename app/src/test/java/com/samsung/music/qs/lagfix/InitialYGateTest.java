package com.samsung.music.qs.lagfix;

import java.lang.reflect.Field;
import org.junit.Test;
import static org.junit.Assert.*;

public class InitialYGateTest {
    private static final class NativeOrigin {
        float initialY = 180f;
    }

    private static InitialYGate gate(NativeOrigin origin) throws Exception {
        Field field = NativeOrigin.class.getDeclaredField("initialY");
        field.setAccessible(true);
        return new InitialYGate(field, origin);
    }

    @Test public void realDeltaAndReleaseOriginArePreserved() throws Exception {
        NativeOrigin origin = new NativeOrigin();
        float fingerY = 240f;
        try (InitialYGate scope = gate(origin)) {
            float nativeDelta = fingerY - origin.initialY;
            scope.allowHeader();
            assertTrue("the eligibility gate accepts the expanded date row", origin.initialY < 48f);
            assertEquals("the delta computed before the gate remains real", 60f, nativeDelta, 0f);
        }
        assertEquals("native touch/release sees the original origin", 180f, origin.initialY, 0f);
        assertEquals(60f, fingerY - origin.initialY, 0f);
    }

    @Test public void exceptionDuringNativeInterceptionStillRestoresOrigin() throws Exception {
        NativeOrigin origin = new NativeOrigin();
        try {
            try (InitialYGate scope = gate(origin)) {
                scope.allowHeader();
                throw new IllegalStateException("native interceptor failed");
            }
        } catch (IllegalStateException expected) {
            assertEquals(180f, origin.initialY, 0f);
        }
    }

    @Test public void anotherDeltaReadTemporarilyRestoresTheRealOrigin() throws Exception {
        NativeOrigin origin = new NativeOrigin();
        try (InitialYGate scope = gate(origin)) {
            scope.allowHeader();
            scope.restore();
            assertEquals(60f, 240f - origin.initialY, 0f);
            scope.allowHeader();
            scope.allowHeader();
        }
        assertEquals("reapplying must not save the synthetic value", 180f, origin.initialY, 0f);
    }

    @Test public void pointerHandoffBeforeDeltaReadPreservesItsUpdatedOrigin() throws Exception {
        NativeOrigin origin = new NativeOrigin();
        try (InitialYGate scope = gate(origin)) {
            origin.initialY = 230f;
            float nativeDelta = 290f - origin.initialY;
            scope.allowHeader();
            assertEquals(60f, nativeDelta, 0f);
        }
        assertEquals(230f, origin.initialY, 0f);
    }

    @Test public void tapWithoutAnAppliedGateNeverMutatesTheOrigin() throws Exception {
        NativeOrigin origin = new NativeOrigin();
        try (InitialYGate scope = gate(origin)) {
            assertFalse(scope.isApplied());
            assertTrue(scope.owns(origin));
            assertFalse(scope.owns(new NativeOrigin()));
        }
        assertEquals(180f, origin.initialY, 0f);
    }
}
