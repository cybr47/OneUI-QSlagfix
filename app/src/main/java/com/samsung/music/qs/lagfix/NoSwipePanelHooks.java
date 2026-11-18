package com.samsung.music.qs.lagfix;

import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

/** Widens the starting area. Samsung owns touch interception, dragging and settlement. */
final class NoSwipePanelHooks {
    private final SamsungMusicWavesHook module;
    private final NativePanels panels;
    private final NotificationPullTargets targets;
    private final WeakHashMap<Object, SwipeSession> sessions = new WeakHashMap<>();
    private final WeakHashMap<View, PendingPull> pending = new WeakHashMap<>();
    private Object checkingHelper;
    private InitialYGate activeGate;
    // Set only while dispatching the MOVE that transfers a child gesture to the panel.
    private View handoffPanel;
    private volatile boolean failed;

    private static final class PendingPull {
        final SwipeSession session;
        final MotionEvent down;

        PendingPull(SwipeSession session, MotionEvent down) {
            this.session = session;
            this.down = MotionEvent.obtainNoHistory(down);
        }
    }

    private NoSwipePanelHooks(SamsungMusicWavesHook module, ClassLoader loader)
            throws ReflectiveOperationException {
        this.module = module;
        panels = new NativePanels(loader);
        NotificationPullTargets resolved = null;
        try {
            resolved = new NotificationPullTargets(loader);
        } catch (ReflectiveOperationException error) {
            module.warn("Notification row lookup unavailable, using the header gesture", error);
        }
        targets = resolved;
    }

    static void install(SamsungMusicWavesHook module, ClassLoader loader) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            NoSwipePanelHooks hooks = new NoSwipePanelHooks(module, loader);
            handles.add(module.hook(hooks.panels.initiate).setId("common-header-start")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (!hooks.failed && mainThread()) {
                            hooks.initialized(chain.getThisObject(), (MotionEvent) chain.getArg(0));
                        }
                        return result;
                    }));
            handles.add(module.hook(hooks.panels.intercept).setId("native-header-interception")
                    .intercept(hooks::intercept));
            handles.add(module.hook(hooks.panels.updateDirection).setId("disable-horizontal-panel-direction")
                    .intercept(hooks::updateDirection));
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
            if (hooks.targets != null) {
                handles.add(module.hook(hooks.targets.interceptPanel).setId("notification-pull-handoff")
                        .intercept(chain -> {
                            if (hooks.handoffPanel == chain.getThisObject()
                                    && ((MotionEvent) chain.getArg(0)).getActionMasked() == MotionEvent.ACTION_MOVE) {
                                hooks.handoffPanel = null;
                                return true;
                            }
                            return chain.proceed();
                        }));
                handles.add(module.hook(hooks.targets.dispatchPanel).setId("notification-body-pull")
                        .intercept(hooks::dispatchPanel));
            }
            if (!module.deoptimize(hooks.panels.intercept)) {
                throw new IllegalStateException("Cannot deoptimize native panel interception");
            }
            module.note("Panel gesture hooks installed (vertical drag/release)");
        } catch (Throwable error) {
            for (int i = handles.size() - 1; i >= 0; i--) handles.get(i).unhook();
            module.warn("Panel gesture hooks could not be installed", error);
        }
    }

    private SwipeSession sessionForDown(Object handler, Object helper, MotionEvent event)
            throws ReflectiveOperationException {
        SwipeSession session = sessions.get(handler);
        if (session != null && session.sameTouch(event.getDownTime(), event.getDeviceId())) return session;
        if (!panels.available(helper)) {
            sessions.remove(handler);
            return null;
        }
        SwipeSession.Start start;
        if (targets != null && panels.notificationPage(helper)) {
            start = targets.classify(panels, helper, event);
        } else {
            start = panels.inHeader(helper, event) ? SwipeSession.Start.HEADER : SwipeSession.Start.NONE;
        }
        session = new SwipeSession(panels.slop(handler), start, event.getDownTime(),
                event.getDeviceId(), event.getRawX(), event.getRawY());
        sessions.put(handler, session);
        return session;
    }

    private void initialized(Object handler, MotionEvent event) {
        try {
            if (event.getActionMasked() != MotionEvent.ACTION_DOWN) {
                // Samsung can start tracking late when a child gives up a stream. A MOVE is
                // never a fresh starting point for a wider panel gesture.
                SwipeSession old = sessions.get(handler);
                if (old != null && old.sameTouch(event.getDownTime(), event.getDeviceId())) {
                    old.invalidateNativeOrigin();
                    panels.setHeaderStart(handler, false);
                } else {
                    sessions.remove(handler);
                }
                return;
            }
            SwipeSession session = sessionForDown(handler, panels.helper(handler), event);
            if (session != null) {
                session.recordNativeDown(event.getX(), event.getY());
                panels.setHeaderStart(handler, session.allowsVertical());
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
        }
    }

    private Object dispatchPanel(XposedInterface.Chain chain) throws Throwable {
        if (failed || !mainThread()) return chain.proceed();
        ViewGroup panel = (ViewGroup) chain.getThisObject();
        MotionEvent event = (MotionEvent) chain.getArg(0);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            clearPending(panel);
            Object result = chain.proceed();
            if (!Boolean.TRUE.equals(result)) return result;
            try {
                Object helper = targets.helper(panel);
                if (panels.notificationPage(helper)) {
                    SwipeSession s = sessionForDown(panels.handler(helper), helper, event);
                    if (s != null && s.start == SwipeSession.Start.NOTIFICATIONS) {
                        pending.put(panel, new PendingPull(s, event));
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                fail(error);
            }
            return result;
        }
        if (terminal(event)) {
            clearPending(panel);
            try {
                return chain.proceed();
            } finally {
                try {
                    if (!sessions.isEmpty()) {
                        Object helper = targets.helper(panel);
                        if (helper != null) finish(helper, event);
                    }
                } catch (IllegalAccessException error) {
                    fail(error);
                }
            }
        }
        PendingPull pull = pending.get(panel);
        if (pull == null) return chain.proceed();
        if (!pull.session.sameTouch(event.getDownTime(), event.getDeviceId())) {
            clearPending(panel);
        } else if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_POINTER_DOWN
                || action == MotionEvent.ACTION_POINTER_UP) {
            SwipeSession s = pull.session;
            SwipeSession.Pull direction = s.notificationPull(event.getRawX() - s.rawX,
                    event.getRawY() - s.rawY, event.getPointerCount());
            if (direction != SwipeSession.Pull.WAIT) {
                pending.remove(panel);
                Object claimed = null;
                try {
                    if (direction == SwipeSession.Pull.DOWN) claimed = claimPull(panel, pull, event);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    fail(error);
                } finally {
                    pull.down.recycle();
                }
                if (claimed != null) return dispatchClaimed(chain, panel, claimed, event);
            }
        }
        return chain.proceed();
    }

    private Object claimPull(View panel, PendingPull pull, MotionEvent move)
            throws ReflectiveOperationException {
        Object helper = targets.helper(panel);
        if (!panels.notificationPage(helper) || !panels.available(helper) || targets.touchOccupied(panel)) {
            return null;
        }
        Object handler = panels.handler(helper);
        if (panels.alreadyIntercepted(handler)) return null;
        // Children that consumed DOWN (notably the media card) may never have initialized
        // Samsung's split handler, or they may have used a different view's local coordinates.
        if (sessions.get(handler) != pull.session || !pull.session.initializedAt(pull.down.getX(), pull.down.getY())) {
            invokeHooked(panels.initiate, handler, pull.down);
        }
        return Boolean.TRUE.equals(invokeHooked(panels.intercept, helper, move)) ? helper : null;
    }

    private Object dispatchClaimed(XposedInterface.Chain chain, ViewGroup panel, Object helper, MotionEvent move)
            throws Throwable {
        // Samsung's panel interceptor explicitly declines when panelSliderIntercepted is set.
        // Intercept this MOVE once so Android cancels the child, even if a media seek bar
        // asked the parent to stay out on DOWN. A horizontal seek never enters this path.
        panel.requestDisallowInterceptTouchEvent(false);
        View previous = handoffPanel;
        handoffPanel = panel;
        try {
            Object result = chain.proceed();
            // With a child target Android uses the first intercepted MOVE to send it CANCEL.
            // Without one, the panel's own touch handler already delivered this MOVE.
            if (handoffPanel == null) {
                try {
                    if (panels.alreadyIntercepted(panels.handler(helper))) {
                        invokeHooked(panels.touch, helper, move);
                    }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    fail(error);
                }
            }
            return result;
        } finally {
            handoffPanel = previous;
        }
    }

    private Object invokeHooked(Method method, Object owner, MotionEvent event) throws ReflectiveOperationException {
        return module.getInvoker(method).setType(XposedInterface.Invoker.Type.Chain.FULL).invoke(owner, event);
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        if (failed || !mainThread()) return chain.proceed();
        MotionEvent event = (MotionEvent) chain.getArg(0);
        Object previousHelper = checkingHelper;
        InitialYGate previous = activeGate;
        boolean resumePrevious = previous != null && previous.isApplied();
        if (resumePrevious) restore(previous);
        activeGate = null;
        checkingHelper = event.getActionMasked() == MotionEvent.ACTION_MOVE ? chain.getThisObject() : null;
        try {
            return chain.proceed();
        } finally {
            restore(activeGate);
            activeGate = previous;
            checkingHelper = previousHelper;
            if (resumePrevious && !failed) {
                try {
                    previous.allowHeader();
                } catch (IllegalAccessException error) {
                    fail(error);
                }
            }
            if (terminal(event)) finish(chain.getThisObject(), event);
        }
    }

    private Object updateDirection(XposedInterface.Chain chain) throws Throwable {
        // Once the splitter owns a DOWN drag, Samsung keeps that direction until release.
        // Its per-frame handleTouch path can skip all the module's hit and direction checks.
        if (checkingHelper == null || failed || !mainThread()) return chain.proceed();
        Object handler = chain.getThisObject();
        SwipeSession s = sessions.get(handler);
        if (s == null) return chain.proceed();
        float dx = ((Number) chain.getArg(0)).floatValue();
        float dy = ((Number) chain.getArg(1)).floatValue();
        try {
            boolean nativeDown = panels.isDown(handler);
            if (s.blockDirection(dx, dy, nativeDown) && panels.available(panels.helper(handler))) {
                panels.blockHorizontal(handler);
                return null;
            }
            if (!nativeDown && s.start == SwipeSession.Start.NOTIFICATIONS
                    && s.notificationPull(dx, dy, 1) == SwipeSession.Pull.PASS) {
                panels.setHeaderStart(handler, false);
            } else if (s.allowsVertical() && panels.helper(handler) == checkingHelper
                    && Math.abs(dy) > Math.abs(dx) && activeGate == null) {
                // onIntercept has already calculated the real finger deltas. Both remaining
                // header checks read initialY, and handleTouch will see the restored origin.
                InitialYGate gate = panels.originGate(handler);
                if (gate != null && panels.available(checkingHelper)) {
                    activeGate = gate;
                    gate.allowHeader();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
        }
        return chain.proceed();
    }

    private void clearPending(View panel) {
        PendingPull pull = pending.remove(panel);
        if (pull != null) pull.down.recycle();
    }

    private void finish(Object helper, MotionEvent event) {
        try {
            Object handler = panels.handler(helper);
            SwipeSession s = sessions.get(handler);
            if (s != null && s.sameTouch(event.getDownTime(), event.getDeviceId())) sessions.remove(handler);
        } catch (IllegalAccessException error) {
            fail(error);
        }
    }

    private void reset(View root) {
        restore(activeGate);
        activeGate = null;
        checkingHelper = null;
        sessions.clear();
        for (PendingPull pull : pending.values()) pull.down.recycle();
        pending.clear();
        panels.clearHeaderArea(root);
    }

    private void restore(InitialYGate gate) {
        if (gate != null) {
            try {
                gate.restore();
            } catch (IllegalAccessException error) {
                fail(error);
            }
        }
    }

    private void fail(Throwable error) {
        if (!failed) {
            failed = true;
            sessions.clear();
            for (PendingPull pull : pending.values()) pull.down.recycle();
            pending.clear();
            module.warn("Panel gestures disabled until SystemUI restarts", error);
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
