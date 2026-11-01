package com.samsung.music.qs.lagfix;

import java.lang.reflect.Field;

/**
 * Widens only Samsung's initialY/header-height eligibility checks inside onIntercept.
 * Pointer deltas must be calculated with the real origin before allowHeader() runs.
 * The real origin is restored before native touch handling or any release animation.
 */
final class InitialYGate implements AutoCloseable {
    private final Field field;
    private final Object receiver;
    private float original;
    private boolean applied;

    InitialYGate(Field field, Object receiver) {
        this.field = field;
        this.receiver = receiver;
    }

    boolean owns(Object handler) {
        return receiver == handler;
    }

    boolean isApplied() {
        return applied;
    }

    void allowHeader() throws IllegalAccessException {
        if (applied) return;
        original = field.getFloat(receiver);
        field.setFloat(receiver, -1f);
        applied = true;
    }

    void restore() throws IllegalAccessException {
        if (!applied) return;
        field.setFloat(receiver, original);
        applied = false;
    }

    @Override public void close() throws IllegalAccessException {
        restore();
    }
}
