package com.samsung.music.qs.lagfix;

import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

/** Own only the existing open-shade header gesture. No view, timer or background thread. */
final class NoSwipePanelHooks {
    private final SamsungMusicWavesHook module;
    private final NativePanels panels;
    private final XposedInterface.Invoker<?, Method> replayDispatch;
    private final WeakHashMap<View, Gesture> gestures = new WeakHashMap<>();
    private boolean replaying;
    private volatile boolean failed;
    private boolean declinedLogged;
    private int debugDownCount;
    private boolean debugInterceptSeen;
    private boolean debugSlideSeen;

    private static final class Gesture {
        final float x, y;
        final SwipeSession session;
        MotionEvent down;
        boolean cancelled;

        Gesture(MotionEvent event, float slop) {
            x = event.getRawX();
            y = event.getRawY();
            session = new SwipeSession(slop);
            down = MotionEvent.obtain(event);
        }

        void recycle() {
            if (down != null) {
                down.recycle();
                down = null;
            }
        }
    }

    private NoSwipePanelHooks(SamsungMusicWavesHook module, ClassLoader loader)
            throws ReflectiveOperationException {
        this.module = module;
        panels = new NativePanels(loader);
        replayDispatch = module.getInvoker(panels.dispatch);
    }

    static void install(SamsungMusicWavesHook module, ClassLoader loader) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            NoSwipePanelHooks hooks = new NoSwipePanelHooks(module, loader);
            // Resolve every contract before installing, and roll back the whole group on failure.
            handles.add(module.hook(hooks.panels.dispatch).setId("panel-status-bar-gesture")
                    .intercept(hooks::dispatch));
            handles.add(module.hook(hooks.panels.intercept).setId("disable-native-panel-swipe")
                    .intercept(chain -> {
                        if (BuildConfig.DEBUG && !hooks.debugInterceptSeen) {
                            hooks.debugInterceptSeen = true;
                            module.debug("First native panel interception: "
                                    + hooks.panels.debugState(chain.getThisObject()), null);
                        }
                        if (!hooks.failed && hooks.panels.available(chain.getThisObject())) return false;
                        return chain.proceed();
                    }));
            handles.add(module.hook(hooks.panels.slide).setId("disable-horizontal-panel-slide")
                    .intercept(chain -> {
                        if (BuildConfig.DEBUG && !hooks.debugSlideSeen) {
                            hooks.debugSlideSeen = true;
                            module.debug("First native panel slide: direction=" + chain.getArg(1)
                                    + "; " + hooks.panels.debugState(chain.getThisObject()), null);
                        }
                        // Only horizontal slides are blocked. Samsung's vertical DOWN frames, which now
                        // run every frame of the animated switch, pass straight through without any
                        // reflective gating.
                        String direction = ((Enum<?>) chain.getArg(1)).name();
                        if (("LEFT".equals(direction) || "RIGHT".equals(direction))
                                && !hooks.failed && hooks.panels.enabled(chain.getThisObject())) {
                            return null;
                        }
                        return chain.proceed();
                    }));
            handles.add(module.hook(hooks.panels.rootDetached).setId("panel-gesture-detach")
                    .intercept(chain -> {
                        hooks.clear((View) chain.getThisObject());
                        return chain.proceed();
                    }));
            handles.add(module.hook(hooks.panels.rootConfig).setId("panel-gesture-configuration")
                    .intercept(chain -> {
                        Gesture gesture = hooks.gestures.get((View) chain.getThisObject());
                        if (gesture != null) {
                            gesture.recycle();
                            gesture.cancelled = true;
                        }
                        return chain.proceed();
                    }));
            // Visible in release builds too: this is the line to look for in the LSPosed log.
            module.note("Panel gesture hooks installed (animated status-bar panel switch)");
        } catch (Throwable error) {
            for (int i = handles.size() - 1; i >= 0; i--) handles.get(i).unhook();
            module.warn("Panel gesture hooks NOT installed; stock Samsung gestures are unchanged", error);
        }
    }

    private Object dispatch(XposedInterface.Chain chain) throws Throwable {
        if (replaying || Looper.myLooper() != Looper.getMainLooper()) return chain.proceed();
        View root = (View) chain.getThisObject();
        MotionEvent event = (MotionEvent) chain.getArg(0);
        int action = event.getActionMasked();
        Gesture gesture = gestures.get(root);
        if (action == MotionEvent.ACTION_DOWN) {
            clear(root); // Includes a lost UP/CANCEL from the previous stream.
            gesture = null;
            if (BuildConfig.DEBUG && debugDownCount < 8) {
                debugDownCount++;
                debugDown(root, event);
            }
        }
        // Nothing of ours is in flight: MOVE/UP of a stream we never buffered belongs to Samsung, and
        // there is no reason to walk the reflective helper chain for every touch event.
        if (gesture == null && action != MotionEvent.ACTION_DOWN) return chain.proceed();
        if (failed) {
            // Never forward an orphan MOVE/UP after a module-owned DOWN.
            if (gesture == null) return chain.proceed();
            if (terminal(action)) clear(root);
            return true;
        }

        Object helper;
        boolean passthrough = false;
        try {
            helper = panels.helper(root);
            if (action == MotionEvent.ACTION_DOWN) {
                boolean candidate = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)
                        && event.getPointerCount() == 1 && root.isShown() && panels.available(helper);
                // Read-only: a tap or an upward swipe must never snap a running panel animation to its end.
                if (candidate && panels.inHeader(helper, event, new int[2])
                        && PanelSwitch.canToggle(panels.access(helper))) {
                    gestures.put(root, new Gesture(event, panels.slop(helper)));
                    // Buffer this DOWN: a downward switch never enters the native collapse/fling
                    // path. Upward streams and taps replay the DOWN before returning to stock.
                    return true;
                }
                passthrough = true;
            } else if (gesture == null) {
                passthrough = true;
            } else {
                if (gesture.cancelled || !panels.available(helper)) {
                    gesture.recycle();
                    gesture.cancelled = true;
                    if (terminal(action)) clear(root);
                    return true;
                }
                if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_OUTSIDE) {
                    clear(root);
                    return true; // No native DOWN was dispatched for this stream.
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
            if (gesture == null) return chain.proceed();
            gesture.recycle();
            if (terminal(action)) clear(root);
            return true;
        }
        // Do not catch exceptions from Samsung/the rest of the hook chain as module failures.
        if (passthrough) return chain.proceed();

        // Multi-pointer gestures before commitment belong to Samsung. Once committed, consume
        // the tail so a second finger or direction reversal cannot toggle a second time.
        SwipeSession.Decision decision;
        if (event.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_UP) {
            decision = gesture.down != null
                    ? SwipeSession.Decision.RELEASE : SwipeSession.Decision.CONSUME;
        } else {
            decision = gesture.session.move(event.getRawX() - gesture.x, event.getRawY() - gesture.y);
        }
        if (decision == SwipeSession.Decision.TOGGLE) {
            gesture.recycle();
            try {
                if (!PanelSwitch.toggle(panels.access(helper)) && !declinedLogged) {
                    // Samsung refused this one switch (state unchanged). That is not a broken contract,
                    // so the feature stays on; the swipe is simply ignored. Logged once per process.
                    declinedLogged = true;
                    module.warn("Samsung declined a panel switch; that swipe was ignored", null);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                fail(error);
            }
            if (terminal(action)) clear(root);
            return true;
        }
        if (decision == SwipeSession.Decision.CONSUME) {
            gesture.recycle();
            if (terminal(action)) clear(root);
            return true;
        }
        if (decision == SwipeSession.Decision.RELEASE || action == MotionEvent.ACTION_UP) {
            MotionEvent down = gesture.down;
            gesture.down = null;
            gestures.remove(root);
            if (down != null) {
                replaying = true;
                try {
                    replayDispatch.invoke(root, down);
                } finally {
                    replaying = false;
                    down.recycle();
                }
            }
            // Stock receives the original DOWN followed by this MOVE/UP, preserving swipe-up close.
            return chain.proceed();
        }
        return true;
    }

    private void fail(Throwable error) {
        failed = true;
        module.warn("Panel gestures disabled until SystemUI restarts: unexpected contract failure", error);
    }

    private void debugDown(View root, MotionEvent event) {
        try {
            Object helper = panels.helper(root);
            boolean available = panels.available(helper);
            boolean candidate = available && root.isShown() && event.getPointerCount() == 1
                    && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN);
            String header = candidate
                    ? Boolean.toString(panels.inHeader(helper, event, new int[2])) : "not evaluated";
            module.debug("Native panel snapshot: touch=" + debugDownCount
                    + "; source=" + event.getSource() + "; pointers=" + event.getPointerCount()
                    + "; rootShown=" + root.isShown() + "; available=" + available
                    + "; inHeader=" + header + "; failed=" + failed
                    + "; " + panels.debugState(helper), null);
        } catch (Throwable error) {
            module.debug("Native panel snapshot failed", error);
        }
    }

    private void clear(View root) {
        Gesture old = gestures.remove(root);
        if (old != null) old.recycle();
    }

    private static boolean terminal(int action) {
        return action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_OUTSIDE;
    }
}
