package com.samsung.music.qs.lagfix;

import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

/**
 * Separate-shade DOWN pulls: keep Samsung's header gestures, but claim a body
 * pull at the outer shade window before a media/empty-area child consumes it.
 * Actual drag progress, velocity and settlement remain owned by Samsung.
 */
final class NoSwipePanelHooks {
    private final SamsungMusicWavesHook module;
    private final NativePanels panels;
    private final NotificationPullTargets targets;
    private final WeakHashMap<Object, SwipeSession> sessions = new WeakHashMap<>();
    private final WeakHashMap<View, PendingPull> pending = new WeakHashMap<>();
    private final WeakHashMap<View, ActivePull> active = new WeakHashMap<>();
    private Object checkingHelper;
    private InitialYGate activeGate;
    private Object latestHelper;
    private boolean suppressCancel;
    private volatile boolean failed;

    private static final class PendingPull {
        final SwipeSession session;
        final Object helper;
        final MotionEvent down;

        PendingPull(SwipeSession session, Object helper, MotionEvent down) {
            this.session = session;
            this.helper = helper;
            this.down = MotionEvent.obtainNoHistory(down);
        }

        void recycle() {
            down.recycle();
        }
    }

    private static final class ActivePull {
        final SwipeSession session;
        final Object helper;

        ActivePull(SwipeSession session, Object helper) {
            this.session = session;
            this.helper = helper;
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
            module.warn("Notification row lookup unavailable; native header gesture only", error);
        }
        targets = resolved;
    }

    static void install(SamsungMusicWavesHook module, ClassLoader loader) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            NoSwipePanelHooks hooks = new NoSwipePanelHooks(module, loader);
            handles.add(module.hook(hooks.panels.initiate).setId("native-panel-start")
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        if (!hooks.failed && mainThread()) {
                            hooks.initialized(chain.getThisObject(), (MotionEvent) chain.getArg(0));
                        }
                        return result;
                    }));
            handles.add(module.hook(hooks.panels.intercept).setId("native-panel-interception")
                    .intercept(hooks::intercept));
            handles.add(module.hook(hooks.panels.touch).setId("ignore-child-handoff-cancel")
                    .intercept(chain -> {
                        MotionEvent event = (MotionEvent) chain.getArg(0);
                        if (hooks.suppressCancel && event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                            return true;
                        }
                        return chain.proceed();
                    }));
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
                handles.add(module.hook(hooks.panels.rootDispatch).setId("shade-window-body-pull")
                        .intercept(hooks::dispatchWindow));
            }
            if (!module.deoptimize(hooks.panels.intercept)) {
                throw new IllegalStateException("Cannot deoptimize native panel interception");
            }
            module.note("Panel gesture hooks installed (shade-window body pull + native drag/release)");
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
            latestHelper = panels.helper(handler);
            if (event.getActionMasked() != MotionEvent.ACTION_DOWN) {
                SwipeSession old = sessions.get(handler);
                if (old != null && old.sameTouch(event.getDownTime(), event.getDeviceId())) {
                    old.invalidateNativeOrigin();
                    panels.setHeaderStart(handler, false);
                } else {
                    sessions.remove(handler);
                }
                return;
            }
            SwipeSession session = sessionForDown(handler, latestHelper, event);
            if (session != null) {
                session.recordNativeDown(event.getX(), event.getY());
                panels.setHeaderStart(handler, session.allowsVertical());
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
        }
    }

    private Object dispatchWindow(XposedInterface.Chain chain) throws Throwable {
        if (failed || !mainThread()) return chain.proceed();
        View window = (View) chain.getThisObject();
        MotionEvent event = (MotionEvent) chain.getArg(0);
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            clearPending(window);
            active.remove(window);
            Object result = chain.proceed();
            // The blank part of the shade may return false on DOWN even though the
            // outer window still receives the remainder of the physical gesture.
            try {
                Object helper = targets.helperFromWindow(window);
                if (helper == null) helper = latestHelper;
                if (panels.notificationPage(helper) && panels.available(helper)) {
                    SwipeSession session = sessionForDown(panels.handler(helper), helper, event);
                    if (session != null && session.start == SwipeSession.Start.NOTIFICATIONS) {
                        pending.put(window, new PendingPull(session, helper, event));
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                fail(error);
            }
            return result;
        }

        ActivePull owned = active.get(window);
        if (owned != null) {
            if (owned.session.sameTouch(event.getDownTime(), event.getDeviceId())) {
                try {
                    invokeHooked(panels.touch, owned.helper, event);
                    if (terminal(event)) {
                        active.remove(window);
                        finish(owned.helper, event);
                    }
                    return true;
                } catch (ReflectiveOperationException | RuntimeException error) {
                    active.remove(window);
                    fail(error);
                    return true; // The child's prior stream was already cancelled.
                }
            }
            active.remove(window);
        }

        PendingPull pull = pending.get(window);
        if (pull == null) return chain.proceed();
        if (!pull.session.sameTouch(event.getDownTime(), event.getDeviceId()) || terminal(event)) {
            clearPending(window);
            return chain.proceed();
        }

        if (action != MotionEvent.ACTION_MOVE && action != MotionEvent.ACTION_POINTER_DOWN
                && action != MotionEvent.ACTION_POINTER_UP) return chain.proceed();

        SwipeSession.Pull decision = pull.session.notificationPull(
                event.getRawX() - pull.session.rawX,
                event.getRawY() - pull.session.rawY, event.getPointerCount());
        if (decision == SwipeSession.Pull.WAIT) return chain.proceed();
        pending.remove(window);
        try {
            if (decision != SwipeSession.Pull.DOWN || !claimPull(pull, event)) {
                return chain.proceed();
            }

            // Previously CANCEL was sent through the outer shade window, whose
            // interaction controller also processes it. That needlessly disturbed
            // the shade's header/date and scrim during a successful handoff.
            // Cancel only the notification content's child stream; do not cancel
            // the entire shade window's native gesture or transition state.
            suppressCancel = true;
            try {
                if (!targets.cancelContentTouch(window, event)) {
                    module.debug("No attached notification content to cancel", null);
                }
            } finally {
                suppressCancel = false;
            }

            active.put(window, new ActivePull(pull.session, pull.helper));
            invokeHooked(panels.touch, pull.helper, event);
            module.debug("Transferred DOWN body pull to native split handler", null);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
            return true;
        } finally {
            pull.recycle();
        }
    }

    private boolean claimPull(PendingPull pull, MotionEvent move)
            throws ReflectiveOperationException {
        Object helper = pull.helper;
        if (!panels.notificationPage(helper) || !panels.available(helper)) return false;
        Object handler = panels.handler(helper);
        if (panels.alreadyIntercepted(handler)) return false;

        // A media child can swallow DOWN, preventing the normal shade path from
        // initializing the split handler. Replay only its original native DOWN,
        // never an artificial header coordinate or a synthetic panel transition.
        if (sessions.get(handler) != pull.session
                || !pull.session.initializedAt(pull.down.getX(), pull.down.getY())) {
            invokeHooked(panels.initiate, handler, pull.down);
        }
        return Boolean.TRUE.equals(invokeHooked(panels.intercept, helper, move));
    }

    private Object invokeHooked(Method method, Object owner, MotionEvent event)
            throws ReflectiveOperationException {
        return module.getInvoker(method).setType(XposedInterface.Invoker.Type.Chain.FULL)
                .invoke(owner, event);
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        MotionEvent event = (MotionEvent) chain.getArg(0);
        if (suppressCancel && event.getActionMasked() == MotionEvent.ACTION_CANCEL) return false;
        if (failed || !mainThread()) return chain.proceed();
        Object previousHelper = checkingHelper;
        InitialYGate previous = activeGate;
        boolean resumePrevious = previous != null && previous.isApplied();
        if (resumePrevious) restore(previous);
        activeGate = null;
        checkingHelper = event.getActionMasked() == MotionEvent.ACTION_MOVE
                ? chain.getThisObject() : null;
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
        if (checkingHelper == null || failed || !mainThread()) return chain.proceed();
        Object handler = chain.getThisObject();
        SwipeSession session = sessions.get(handler);
        if (session == null) return chain.proceed();
        float dx = ((Number) chain.getArg(0)).floatValue();
        float dy = ((Number) chain.getArg(1)).floatValue();
        try {
            boolean nativeDown = panels.isDown(handler);
            if (session.blockDirection(dx, dy, nativeDown)
                    && panels.available(panels.helper(handler))) {
                panels.blockHorizontal(handler);
                return null;
            }
            if (!nativeDown && session.start == SwipeSession.Start.NOTIFICATIONS
                    && session.notificationPull(dx, dy, 1) == SwipeSession.Pull.PASS) {
                panels.setHeaderStart(handler, false);
            } else if (session.allowsVertical() && panels.helper(handler) == checkingHelper
                    && Math.abs(dy) > Math.abs(dx) && activeGate == null) {
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

    private void clearPending(View view) {
        PendingPull pull = pending.remove(view);
        if (pull != null) pull.recycle();
    }

    private void finish(Object helper, MotionEvent event) {
        try {
            Object handler = panels.handler(helper);
            SwipeSession session = sessions.get(handler);
            if (session != null && session.sameTouch(event.getDownTime(), event.getDeviceId())) {
                sessions.remove(handler);
            }
        } catch (IllegalAccessException error) {
            fail(error);
        }
    }

    private void reset(View root) {
        restore(activeGate);
        activeGate = null;
        checkingHelper = null;
        latestHelper = null;
        sessions.clear();
        for (PendingPull pull : pending.values()) pull.recycle();
        pending.clear();
        active.clear();
        panels.clearHeaderArea(root);
    }

    private void restore(InitialYGate gate) {
        if (gate == null) return;
        try {
            gate.restore();
        } catch (IllegalAccessException error) {
            fail(error);
        }
    }

    private void fail(Throwable error) {
        if (failed) return;
        failed = true;
        sessions.clear();
        for (PendingPull pull : pending.values()) pull.recycle();
        pending.clear();
        active.clear();
        module.warn("Panel gestures disabled until SystemUI restarts", error);
    }

    private static boolean mainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    private static boolean terminal(MotionEvent event) {
        int action = event.getActionMasked();
        return action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
    }
}
