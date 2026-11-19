package com.samsung.music.qs.lagfix;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;

/**
 * Diagnostic variant of the minimal native release-curve experiment.
 *
 * The original experiment did not log successful application in RELEASE APKs,
 * so an "installed" line was not proof that Samsung ever invoked the hook.
 *
 * Observe only the first 12 native release attempts per SystemUI process.
 * No per-frame hooks, extra animations or view transformations are introduced.
 */
final class NativeHorizontalMotionHooks {
    private static final int MAX_DIAGNOSTIC_CALLS = 12;

    private final SamsungMusicWavesHook module;
    private final Method createRelease;
    private final Field direction;
    private final Field nativeAnimator;
    private final TimeInterpolator springCurve = NativeSpringCurve::value;
    private int releaseCalls;
    private int applied;
    private int exceptions;

    private NativeHorizontalMotionHooks(SamsungMusicWavesHook module, ClassLoader loader)
            throws ReflectiveOperationException {
        this.module = module;
        Class<?> handler = loader.loadClass("com.android.systemui.shade.PanelSlideEventHandler");
        createRelease = handler.getDeclaredMethod("createSlideAnimatorAndRun", float.class, float.class);
        createRelease.setAccessible(true);
        direction = handler.getDeclaredField("direction");
        direction.setAccessible(true);
        nativeAnimator = handler.getDeclaredField("sliderAnimator");
        nativeAnimator.setAccessible(true);
        if (nativeAnimator.getType() != ValueAnimator.class) {
            throw new NoSuchFieldException("Unexpected PanelSlideEventHandler.sliderAnimator type");
        }
    }

    static void install(SamsungMusicWavesHook module, ClassLoader loader) {
        XposedInterface.HookHandle handle = null;
        try {
            NativeHorizontalMotionHooks adapter = new NativeHorizontalMotionHooks(module, loader);
            handle = module.hook(adapter.createRelease).setId("native-horizontal-spring-release")
                    .intercept(adapter::release);
            module.note("Native release diagnostic hook installed; observing first "
                    + MAX_DIAGNOSTIC_CALLS + " invocations (stock panel animation)");
        } catch (Throwable error) {
            if (handle != null) handle.unhook();
            module.warn("Native release diagnostics unavailable; stock animation retained", error);
        }
    }

    private void report(int call, String detail) {
        if (call <= MAX_DIAGNOSTIC_CALLS) {
            module.note("Native release diagnostic #" + call + ": " + detail);
        }
    }

    private Object release(XposedInterface.Chain chain) throws Throwable {
        // This is the important check: print a RELEASE-build line on actual
        // interception, not only when a hook registration succeeds.
        final int call = ++releaseCalls;
        if (call <= MAX_DIAGNOSTIC_CALLS) {
            report(call, "method invoked, mainThread="
                    + (Looper.myLooper() == Looper.getMainLooper()));
        }

        // Never skip Samsung's original body. Its listeners, state updates,
        // animator ownership and completion are mandatory.
        Object result = chain.proceed();

        if (Looper.myLooper() != Looper.getMainLooper()) {
            report(call, "skipped: call not on UI thread");
            return result;
        }
        try {
            Object handler = chain.getThisObject();
            Object nativeDirection = direction.get(handler);
            if (!(nativeDirection instanceof Enum<?>)) {
                report(call, "skipped: direction not an enum: " + nativeDirection);
                return result;
            }
            String name = ((Enum<?>) nativeDirection).name();
            if (!"LEFT".equals(name) && !"RIGHT".equals(name)) {
                report(call, "skipped: native direction=" + name);
                return result;
            }

            Object found = nativeAnimator.get(handler);
            if (!(found instanceof ValueAnimator)) {
                report(call, "skipped: sliderAnimator is "
                        + (found == null ? "null" : found.getClass().getName()));
                return result;
            }
            ValueAnimator animator = (ValueAnimator) found;
            long duration = animator.getDuration();
            boolean started = animator.isStarted();
            boolean running = animator.isRunning();
            TimeInterpolator previous = animator.getInterpolator();

            if (!started || duration <= 0L) {
                report(call, "skipped: direction=" + name
                        + ", started=" + started + ", running=" + running
                        + ", duration=" + duration + "ms");
                return result;
            }

            animator.setInterpolator(springCurve);
            applied++;
            report(call, "APPLIED: direction=" + name
                    + ", started=" + started + ", running=" + running
                    + ", duration=" + duration + "ms, previous="
                    + (previous == null ? "null" : previous.getClass().getName())
                    + ", totalApplied=" + applied);
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (exceptions++ == 0) {
                module.warn("Native release diagnostic exception; Samsung transition retained", error);
            }
        }
        return result;
    }
}
