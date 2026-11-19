package com.samsung.music.qs.lagfix;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;

/**
 * Headless GlassUI-inspired motion adapter for Samsung's stock separate panels.
 *
 * We do not animate, translate, hide or layer any View. Samsung continues to
 * own touch gestures, per-frame panel progress, all visible UI and final state.
 * The only override is the native horizontal release animator's interpolator.
 *
 * Native ValueAnimator already runs against Choreographer vsync timestamps.
 * Using that same animator rather than adding a second frame driver avoids
 * double updates, extra per-frame work and competing transition state.
 */
final class NativeHorizontalMotionHooks {
    private final SamsungMusicWavesHook module;
    private final Method createRelease;
    private final Field direction;
    private final Field nativeAnimator;
    private final TimeInterpolator springCurve = NativeSpringCurve::value;
    private int applied;
    private int skipped;

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
            module.note("Native horizontal spring release hook installed (stock animator, no View hooks)");
        } catch (Throwable error) {
            if (handle != null) handle.unhook();
            module.warn("Native spring release unavailable; stock Samsung animation retained", error);
        }
    }

    private Object release(XposedInterface.Chain chain) throws Throwable {
        // Samsung must create, start and own its animation and callbacks first.
        Object result = chain.proceed();
        if (Looper.myLooper() != Looper.getMainLooper()) return result;
        try {
            Object handler = chain.getThisObject();
            Object nativeDirection = direction.get(handler);
            if (!(nativeDirection instanceof Enum<?>)) return result;
            String name = ((Enum<?>) nativeDirection).name();
            if (!"LEFT".equals(name) && !"RIGHT".equals(name)) return result;

            Object found = nativeAnimator.get(handler);
            if (!(found instanceof ValueAnimator)) return result;
            ValueAnimator animator = (ValueAnimator) found;
            // Skip a completed, delayed, or non-running animator rather than
            // risking a second ownership path or changing an unrelated state.
            if (!animator.isStarted() || animator.getDuration() <= 0L) return result;

            animator.setInterpolator(springCurve);
            if (applied < 3) {
                applied++;
                module.debug("Native horizontal spring curve attached; duration="
                        + animator.getDuration() + "ms direction=" + name, null);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            // A firmware or timing mismatch must not interrupt native release.
            if (skipped++ == 0) {
                module.warn("Native spring release skipped; keeping Samsung transition", error);
            }
        }
        return result;
    }
}
