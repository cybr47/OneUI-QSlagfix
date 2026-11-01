package com.samsung.music.qs.lagfix;

import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

/** Samsung receives the original touch stream, including button presses and drag/release/cancel. */
final class NoSwipePanelHooks {
    private final SamsungMusicWavesHook module;
    private final NativePanels panels;
    private final WeakHashMap<Object, SwipeSession> sessions = new WeakHashMap<>();
    private InitialYGate activeGate;
    private volatile boolean failed;
    private int debugDownCount;

    private NoSwipePanelHooks(SamsungMusicWavesHook module, ClassLoader loader)
            throws ReflectiveOperationException {
        this.module = module;
        panels = new NativePanels(loader);
    }

    static void install(SamsungMusicWavesHook module, ClassLoader loader) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            NoSwipePanelHooks hooks = new NoSwipePanelHooks(module, loader);
            handles.add(module.hook(hooks.panels.initiate).setId("common-header-start")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (!hooks.failed && mainThread()) {
                            hooks.initialize(chain.getThisObject(), (MotionEvent) chain.getArg(0));
                        }
                        return result;
                    }));
            handles.add(module.hook(hooks.panels.intercept).setId("native-header-interception")
                    .intercept(hooks::intercept));
            handles.add(module.hook(hooks.panels.pointerDeltas).setId("preserve-real-pointer-deltas")
                    .intercept(chain -> {
                        InitialYGate gate = hooks.activeGate;
                        if (!mainThread() || gate == null || !gate.owns(chain.getThisObject())) return chain.proceed();
                        // Even a repeated/nested delta read uses the real finger origin.
                        hooks.restore(gate);
                        Object result = chain.proceed();
                        if (!hooks.failed) {
                            try {
                                gate.allowHeader();
                            } catch (ReflectiveOperationException | RuntimeException error) {
                                hooks.fail(error);
                            }
                        }
                        return result;
                    }));
            handles.add(module.hook(hooks.panels.updateDirection).setId("disable-horizontal-panel-direction")
                    .intercept(chain -> {
                        if (!hooks.failed && mainThread()) {
                            try {
                                Object handler = chain.getThisObject();
                                SwipeSession session = hooks.sessions.get(handler);
                                if (session != null && session.blockDirection(
                                        ((Number) chain.getArg(0)).floatValue(),
                                        ((Number) chain.getArg(1)).floatValue(), hooks.panels.isDown(handler))
                                        && hooks.panels.available(hooks.panels.helper(handler))) {
                                    hooks.panels.blockHorizontal(handler);
                                    return null;
                                }
                            } catch (ReflectiveOperationException | RuntimeException error) {
                                hooks.fail(error);
                            }
                        }
                        return chain.proceed();
                    }));
            handles.add(module.hook(hooks.panels.touch).setId("native-release-cleanup")
                    .intercept(chain -> {
                        try {
                            // Native MOVE follows the finger; native UP/CANCEL decides how to settle.
                            return chain.proceed();
                        } finally {
                            if (terminal((MotionEvent) chain.getArg(0))) hooks.finish(chain.getThisObject());
                        }
                    }));
            handles.add(module.hook(hooks.panels.rootDetached).setId("panel-gesture-detach")
                    .intercept(chain -> {
                        hooks.reset((View) chain.getThisObject());
                        return chain.proceed();
                    }));
            handles.add(module.hook(hooks.panels.rootConfig).setId("panel-gesture-configuration")
                    .intercept(chain -> {
                        hooks.reset((View) chain.getThisObject());
                        return chain.proceed();
                    }));
            // This caller must execute its hooked initiation/delta/direction methods, even if ART
            // previously inlined them. Do not deoptimize native touch, rendering or release animation.
            if (!module.deoptimize(hooks.panels.intercept)) {
                throw new IllegalStateException("Cannot deoptimize native panel interception");
            }
            module.note("Panel gesture hooks installed (native header drag/release)");
        } catch (Throwable error) {
            for (int i = handles.size() - 1; i >= 0; i--) handles.get(i).unhook();
            module.warn("Panel gesture hooks NOT installed; stock Samsung gestures are unchanged", error);
        }
    }

    private void initialize(Object handler, MotionEvent event) {
        sessions.remove(handler);
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
        try {
            Object helper = panels.helper(handler);
            if (!panels.available(helper)) return;
            boolean header = panels.inHeader(helper, event);
            sessions.put(handler, new SwipeSession(panels.slop(handler), header));
            // Replace only the native left/right start-area decision, after native initialization.
            panels.setHeaderStart(handler, header);
            if (BuildConfig.DEBUG && debugDownCount < 8) {
                debugDownCount++;
                module.debug("Native header start: sharedHeader=" + header, null);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
        }
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        if (failed || !mainThread()) return chain.proceed();
        MotionEvent event = (MotionEvent) chain.getArg(0);
        InitialYGate previous = activeGate;
        boolean resumePrevious = previous != null && previous.isApplied();
        if (resumePrevious) restore(previous);
        Object handler = null;
        InitialYGate gate = null;
        try {
            handler = panels.handler(chain.getThisObject());
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) sessions.remove(handler);
            SwipeSession session = sessions.get(handler);
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && session != null
                    && session.allowHeaderGate()) {
                // Cheap native-state checks first; the long availability chain only when a gate is needed.
                gate = panels.originGate(handler);
                if (gate != null && !panels.available(chain.getThisObject())) gate = null;
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
        }
        activeGate = gate;
        try {
            // Native onIntercept retains all expansion, keyguard, Compose and touch-slop checks.
            return chain.proceed();
        } finally {
            restore(gate);
            activeGate = previous;
            if (resumePrevious && !failed) {
                try {
                    previous.allowHeader();
                } catch (ReflectiveOperationException | RuntimeException error) {
                    fail(error);
                }
            }
            if (terminal(event) && handler != null) sessions.remove(handler);
        }
    }

    private void finish(Object helper) {
        try {
            sessions.remove(panels.handler(helper));
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
        }
    }

    private void reset(View root) {
        restore(activeGate);
        activeGate = null;
        sessions.clear();
        panels.clearHeaderArea(root);
    }

    private void restore(InitialYGate gate) {
        if (gate == null) return;
        try {
            gate.restore();
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
        }
    }

    private void fail(Throwable error) {
        if (!failed) {
            failed = true;
            sessions.clear();
            module.warn("Panel gestures disabled until SystemUI restarts: unexpected contract failure", error);
        }
    }

    private static boolean mainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    private static boolean terminal(MotionEvent event) {
        int action = event.getActionMasked();
        return action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
    }
}
