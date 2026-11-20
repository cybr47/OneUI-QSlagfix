package com.samsung.music.qs.lagfix;

import android.os.Looper;
import android.view.View;
import android.view.ViewParent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

/** Applies GlassUI-style crossfade to native Samsung panels without spring
 * follow, inertia, elastic scaling, extra Choreographer work or stock visual
 * transforms. Samsung owns gesture progress, completion and panel state.
 */
final class HorizontalPanelTransitionHooks {
    private final SamsungMusicWavesHook module;
    private final HorizontalTransitionPanels panels;
    private final WeakHashMap<Object, HorizontalSwipeSession> sessions = new WeakHashMap<>();
    private final WeakHashMap<Object, HorizontalSwipeSession> adapterSessions = new WeakHashMap<>();
    private int activeSessionCount;
    private boolean internalSlideFractionWrite;
    private volatile boolean failed;
    private int observedHorizontalFrames;
    private final TransitionConstraintGate constraintGate = new TransitionConstraintGate();
    private int redundantConstraintCalls;
    private int reportedConstraintSessions;
    private final AnimatorFanoutGate fanoutGate = new AnimatorFanoutGate();
    private int redundantManagerFanouts;
    private int reportedManagerSessions;

    private HorizontalPanelTransitionHooks(SamsungMusicWavesHook module, ClassLoader loader)
            throws ReflectiveOperationException {
        this.module = module;
        this.panels = new HorizontalTransitionPanels(loader);
    }

    static void install(SamsungMusicWavesHook module, ClassLoader loader) {
        List<XposedInterface.HookHandle> handles = new ArrayList<>();
        try {
            HorizontalPanelTransitionHooks hooks = new HorizontalPanelTransitionHooks(module, loader);
            handles.add(module.hook(hooks.panels.slide)
                    .setId("horizontal-panel-slide")
                    .intercept(hooks::interceptSlide));
            handles.add(module.hook(hooks.panels.onTransitionStateChanged)
                    .setId("horizontal-panel-state")
                    .intercept(hooks::interceptStateChanged));
            handles.add(module.hook(hooks.panels.clearAnimationState)
                    .setId("horizontal-panel-clear")
                    .intercept(hooks::interceptClearAnimationState));
            if (hooks.panels.onPanelClosed != null) {
                handles.add(module.hook(hooks.panels.onPanelClosed)
                        .setId("horizontal-panel-closed")
                        .intercept(hooks::interceptClearAnimationState));
            }
            handles.add(module.hook(hooks.panels.setSlideFraction)
                    .setId("horizontal-compose-slide-fraction")
                    .intercept(hooks::interceptSetSlideFraction));
            if (hooks.panels.setBlurAlphaFromParent != null) {
                handles.add(module.hook(hooks.panels.setBlurAlphaFromParent)
                        .setId("horizontal-notif-blur-alpha")
                        .intercept(hooks::interceptSetBlurAlphaFromParent));
            }
            // Optional, firmware-specific optimization: a Samsung listener
            // posts a full ConstraintSet.clone/applyTo on EVERY fraction update
            // even though updateConstraints$1 does not consume the fraction.
            // Failure to resolve it must never disable the working fade hooks.
            try {
                Class<?> listenerType = Class.forName(
                        "com.android.systemui.shade.SecNotificationsQSContainerController"
                        + "$panelTransitionStateListener$1", false, loader);
                Class<?> eventType = Class.forName(
                        "com.android.systemui.shade.PanelTransitionStateChangeEvent",
                        false, loader);
                Method listenerCallback = listenerType.getDeclaredMethod(
                        "onPanelTransitionStateChanged", eventType);
                listenerCallback.setAccessible(true);
                handles.add(module.hook(listenerCallback)
                        .setId("coalesce-native-container-constraint-rebuild")
                        .intercept(hooks::interceptContainerConstraintEvent));
                module.note("Native transition constraint coalescer installed"
                        + " (retain first/final/layout updates)");
            } catch (Throwable optionalError) {
                module.warn("Native constraint coalescer unavailable;"
                        + " Glass Fade remains enabled", optionalError);
            }
            // Second optional optimization from Samsung's actual DEX:
            // SecQSImplAnimatorManager allocates a Consumer + Java Stream and
            // fans out every duplicate state-2 drag notification to seven
            // animators. The per-frame panel position is handled by slide(),
            // and the children consume only state/enabled, not drag fraction.
            // Keep the initial event, enabled changes and every final state.
            try {
                Class<?> managerType = Class.forName(
                        "com.android.systemui.qs.animator.SecQSImplAnimatorManager",
                        false, loader);
                Class<?> eventType = Class.forName(
                        "com.android.systemui.shade.PanelTransitionStateChangeEvent",
                        false, loader);
                Method stateCallback = managerType.getDeclaredMethod(
                        "onPanelTransitionStateChanged", eventType);
                stateCallback.setAccessible(true);
                handles.add(module.hook(stateCallback)
                        .setId("coalesce-native-qs-animator-fanout")
                        .intercept(hooks::interceptAnimatorFanout));
                module.note("Native QS animator fan-out coalescer installed"
                        + " (retain first/enabled/final events)");
            } catch (Throwable optionalError) {
                module.warn("QS animator fan-out optimization unavailable;"
                        + " Glass Fade and constraint coalescing remain enabled", optionalError);
            }
            module.note("Glass fade-native horizontal hooks installed"
                    + " (direct progress, no bounce or extra frame loop)");
        } catch (Throwable error) {
            for (int i = handles.size() - 1; i >= 0; i--) {
                handles.get(i).unhook();
            }
            module.warn("Horizontal panel transition hooks NOT installed; stock Samsung transitions are unchanged", error);
        }
    }

    private Object interceptSlide(XposedInterface.Chain chain) throws Throwable {
        if (failed || !mainThread()) {
            return chain.proceed();
        }
        Object animator = chain.getThisObject();
        if (animator == null) {
            return chain.proceed();
        }

        try {
            Object direction = chain.getArg(2);
            String directionName = panels.directionName(direction);
            float overSlideX = ((Number) chain.getArg(1)).floatValue();
            int transitionState = panels.getTransitionState(animator);

            boolean activeHorizontal = directionName != null
                    && overSlideX == 0.0f
                    && transitionState == HorizontalTransitionMath.TRANSITION_STATE
                    && panels.isSplitEnabled(animator)
                    && !panels.isThereNoView(animator);

            if (!activeHorizontal) {
                // If an active horizontal swipe session just transitioned to a settled slide call
                // (or overslide/vertical slide), restore all saved prior view properties first and
                // let Samsung's native slide method handle the non-horizontal/settled branch.
                HorizontalSwipeSession existing = sessions.get(animator);
                if (existing != null && existing.isActive()) {
                    endSession(animator, existing, transitionState, false);
                }
                return chain.proceed();
            }

            float fraction = ((Number) chain.getArg(0)).floatValue();
            int stateToChange = ((Number) chain.getArg(3)).intValue();

            HorizontalSwipeSession session = sessions.get(animator);
            if (session == null) {
                session = new HorizontalSwipeSession();
                sessions.put(animator, session);
            }
            if (!session.isActive()) {
                session.begin();
                if (activeSessionCount == 0) {
                    constraintGate.start();
                    redundantConstraintCalls = 0;
                    fanoutGate.reset();
                    redundantManagerFanouts = 0;
                }
                activeSessionCount++;
                if (activeSessionCount == 1) {
                    ColdGpuPipelineWarmup.markHorizontalGesture(true);
                }
                panels.bindSession(animator, session);
                if (session.composeAdapter != null) {
                    adapterSessions.put(session.composeAdapter, session);
                }
            }

            if (session.qsRootView == null || session.shadeRootView == null) {
                endSession(animator, session, transitionState, false);
                return chain.proceed();
            }

            float width = panels.resolvePanelWidth(session);
            if (width <= 0.0f) {
                return chain.proceed();
            }

            HorizontalTransitionMath.FrameTransform frame =
                    HorizontalTransitionMath.FrameTransform.forSlide(
                            transitionState,
                            fraction,
                            overSlideX,
                            directionName,
                            stateToChange,
                            width,
                            session.reversed);
            if (!frame.activeHorizontal) {
                return chain.proceed();
            }

            pinComposeSlideFractionOnce(session);
            panels.prepareDrawables(session);
            applyGlassFadeFrame(session, frame.qsShown, width);
            if (observedHorizontalFrames++ < 3) {
                module.note("Glass fade frame applied; fraction=" + fraction
                        + " toQs=" + frame.movingToQs + " width=" + width);
            }
            return null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
            return chain.proceed();
        }
    }

    private void applyGlassFadeFrame(HorizontalSwipeSession session, float qsShown, float width)
            throws ReflectiveOperationException {
        if (!session.isActive() || width <= 0f) return;
        float qs = GlassFadeMath.clamp01(qsShown);
        float sign = session.reversed ? -1f : 1f;
        float qsX = sign * width * (1f - qs);
        float notiX = -sign * width * qs;
        float qsAlpha = GlassFadeMath.alpha(qs);
        float notificationAlpha = GlassFadeMath.alpha(1f - qs);
        session.applyPageTransform(session.qsRootView, session.qsAnimView,
                qsX, qsAlpha, true, panels);
        session.applyPageTransform(session.shadeRootView, session.shadeAnimView,
                notiX, notificationAlpha, true, panels);
        if (!isDescendantOf(session.buttonView, session.shadeRootView)) {
            session.applyPageTransform(session.buttonView, session.buttonAnimView,
                    notiX, notificationAlpha, true, panels);
        }
        if (!isDescendantOf(session.clockDateView, session.shadeRootView)) {
            session.applyPageTransform(session.clockDateView, session.clockDateAnimView,
                    notiX, notificationAlpha, true, panels);
        }
        session.keepStationaryX(session.headerView, session.headerAnimView, panels);
        session.keepStationaryX(session.plmnView, session.plmnAnimView, panels);
        session.keepStationaryX(session.qsButtonsContainer, null, panels);
    }

    private static boolean isDescendantOf(View child, View ancestor) {
        if (child == null || ancestor == null || child == ancestor) return child == ancestor;
        ViewParent current = child.getParent();
        while (current instanceof View view) {
            if (view == ancestor) return true;
            current = view.getParent();
        }
        return false;
    }

    /**
     * Samsung's container listener posts a new Runnable which clones and
     * reapplies its entire ConstraintSet on every transient fraction update.
     * That underlying routine never reads draggedFraction or the event;
     * only the first notification per horizontal session is needed.
     *
     * This hook does NOT modify the native event, its StateFlow, other
     * listeners, normal layout callbacks or completion-state dispatch.
     */
    private Object interceptContainerConstraintEvent(XposedInterface.Chain chain)
            throws Throwable {
        if (failed || !mainThread() || activeSessionCount <= 0) {
            return chain.proceed();
        }
        try {
            int state = panels.getEventState(chain.getArg(0));
            if (!constraintGate.shouldDispatch(state, true)) {
                redundantConstraintCalls++;
                return null;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A changed SystemUI event contract must never suppress layout.
        }
        return chain.proceed();
    }

    /**
     * Do not run the native QS animator-manager's Stream.filter().forEach()
     * pipeline for repeated identical transient-state events. Keep every
     * callback outside a currently active ShadeSwitch horizontal gesture;
     * always forward the initial transient state, enabled changes and final
     * QS/SHADE/COMBINED states. Native slide() still receives every fraction.
     *
     * Fail open if this specific Samsung event contract does not match.
     */
    private Object interceptAnimatorFanout(XposedInterface.Chain chain)
            throws Throwable {
        if (failed || !mainThread() || activeSessionCount <= 0) {
            return chain.proceed();
        }
        try {
            Object event = chain.getArg(0);
            int state = panels.getEventState(event);
            boolean splitEnabled = panels.isEventSplitEnabled(event);
            if (!fanoutGate.shouldForward(state, splitEnabled, true)) {
                redundantManagerFanouts++;
                return null;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Always preserve the native manager's behavior on unexpected ROMs.
        }
        return chain.proceed();
    }

    private Object interceptSetSlideFraction(XposedInterface.Chain chain) throws Throwable {
        if (failed || !mainThread() || internalSlideFractionWrite) {
            return chain.proceed();
        }
        Object adapter = chain.getThisObject();
        HorizontalSwipeSession session = adapter != null ? adapterSessions.get(adapter) : null;
        if (session != null && session.isActive()) {
            float requested = ((Number) chain.getArg(0)).floatValue();
            if (Math.abs(requested - HorizontalTransitionMath.COMPOSE_SLIDE_FRACTION_VISIBLE) > 0.0005f) {
                if (!session.isComposeSlideFractionPinned()) {
                    pinComposeSlideFractionOnce(session);
                }
                return null;
            }
        }
        return chain.proceed();
    }

    private Object interceptSetBlurAlphaFromParent(XposedInterface.Chain chain) throws Throwable {
        if (failed || !mainThread() || activeSessionCount <= 0) {
            return chain.proceed();
        }
        Object viewObj = chain.getThisObject();
        if (!(viewObj instanceof View rowView)) {
            return chain.proceed();
        }
        float parentAlpha = HorizontalTransitionMath.clamp01(((Number) chain.getArg(0)).floatValue());
        float rowAlpha = rowView.getAlpha();
        boolean drawingAppear = panels.isDrawingAppearAnimation(viewObj);
        float contentAlpha = drawingAppear ? panels.getNotificationContentAlpha(viewObj) : 1.0f;
        float effective = HorizontalTransitionMath.effectiveNotificationBlurAlpha(
                parentAlpha, rowAlpha, drawingAppear, contentAlpha);
        if (Math.abs(parentAlpha - effective) <= 0.0005f) {
            return chain.proceed();
        }
        try {
            return chain.proceed(new Object[]{effective});
        } finally {
            // Keep mBlurAlphaFromParent at the unmultiplied parentAlpha so subsequent calls to
            // ActivatableNotificationView.setAlpha(rowAlpha) -> setBlurAlphaFromParent(mBlurAlphaFromParent)
            // never multiply rowAlpha a second time.
            panels.restoreRawBlurAlphaFromParent(viewObj, parentAlpha);
        }
    }

    private Object interceptStateChanged(XposedInterface.Chain chain) throws Throwable {
        if (failed || !mainThread()) {
            return chain.proceed();
        }
        Object animator = chain.getThisObject();
        HorizontalSwipeSession session = animator != null ? sessions.get(animator) : null;
        if (session == null || !session.isActive()) {
            // No horizontal swipe was active: leave ordinary shade/lockscreen transitions untouched.
            return chain.proceed();
        }

        int newState;
        float eventFraction;
        try {
            Object event = chain.getArg(0);
            newState = panels.getEventState(event);
            eventFraction = panels.getEventFraction(event);
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
            return chain.proceed();
        }

        if (newState == HorizontalTransitionMath.TRANSITION_STATE) {
            // SecPanelSplitHelper.setDraggedFraction fires onPanelTransitionStateChanged(TRANSITION_STATE)
            // on every drag frame. Skip redundant per-frame state-change handling while the horizontal
            // session is active so stock PanelTransitionAnimator does not churn button/clock visibility
            // or prematurely call clearAnimationState() before updateState(stateToChange) runs.
            return null;
        }

        // An active horizontal swipe is settling to QS_STATE (0), SHADE_STATE (1), or COMBINED_STATE (3).
        // First restore the exact saved prior values on all touched views (including ancestors and
        // ComposeView children), then run native onPanelTransitionStateChanged.
        Object composeAdapter = session.composeAdapter;
        Object slideFractionFlow = session.slideFractionFlow;
        Object splitHelper = session.splitHelper;
        try {
            endSession(animator, session, newState, false);
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
        }

        Object result = chain.proceed();

        if (!failed && (newState == HorizontalTransitionMath.QS_STATE
                || newState == HorizontalTransitionMath.SHADE_STATE)) {
            try {
                // When a horizontal swipe cancels/reverses back to the starting page, eventFraction
                // is 0.0f and stock onPanelTransitionStateChanged skips clearAnimationState().
                if (eventFraction < 1.0f && !panels.isUserChanged(animator)) {
                    panels.invokeClearAnimationState(animator);
                    panels.invokeUpdatePanelVisibility(splitHelper);
                }
                writeComposeSlideFractionInternal(
                        composeAdapter,
                        slideFractionFlow,
                        HorizontalTransitionMath.settledSlideFraction(newState));
            } catch (ReflectiveOperationException | RuntimeException error) {
                fail(error);
            }
        }
        return result;
    }

    private Object interceptClearAnimationState(XposedInterface.Chain chain) throws Throwable {
        if (failed || !mainThread()) {
            return chain.proceed();
        }
        Object animator = chain.getThisObject();
        HorizontalSwipeSession session = animator != null ? sessions.get(animator) : null;
        if (session == null || !session.isActive()) {
            // No horizontal swipe was active: do not touch views during ordinary shade animations.
            return chain.proceed();
        }

        try {
            int state = panels.getTransitionState(animator);
            endSession(animator, session, state, true);
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
        }
        return chain.proceed();
    }

    private void pinComposeSlideFractionOnce(HorizontalSwipeSession session)
            throws ReflectiveOperationException {
        if (session.isComposeSlideFractionPinned()) {
            return;
        }
        Float prior = panels.readComposeSlideFraction(session.slideFractionFlow);
        session.markComposeSlideFractionPinned(prior);
        writeComposeSlideFractionInternal(
                session.composeAdapter,
                session.slideFractionFlow,
                HorizontalTransitionMath.COMPOSE_SLIDE_FRACTION_VISIBLE);
    }

    private void writeComposeSlideFractionInternal(
            Object composeAdapter, Object slideFractionFlow, float targetFraction)
            throws ReflectiveOperationException {
        internalSlideFractionWrite = true;
        try {
            panels.writeComposeSlideFractionIfNeeded(composeAdapter, slideFractionFlow, targetFraction);
        } finally {
            internalSlideFractionWrite = false;
        }
    }

    private void endSession(
            Object animator,
            HorizontalSwipeSession session,
            int transitionState,
            boolean restoreComposeSlideFraction)
            throws ReflectiveOperationException {
        Float priorSlideFraction = session.getSavedComposeSlideFraction();
        Object composeAdapter = session.composeAdapter;
        Object slideFractionFlow = session.slideFractionFlow;

        if (session.isActive() && activeSessionCount > 0) {
            activeSessionCount--;
            if (activeSessionCount == 0) {
                ColdGpuPipelineWarmup.markHorizontalGesture(false);
            }
        }
        if (activeSessionCount == 0 && redundantConstraintCalls > 0
                && reportedConstraintSessions < 3) {
            reportedConstraintSessions++;
            module.note("Native constraint coalescer: skipped "
                    + redundantConstraintCalls + " redundant rebuild requests"
                    + " during horizontal gesture");
        }
        if (activeSessionCount == 0 && redundantManagerFanouts > 0
                && reportedManagerSessions < 3) {
            reportedManagerSessions++;
            module.note("Native QS animator fan-out coalescer: skipped "
                    + redundantManagerFanouts
                    + " redundant transient state notifications");
        }
        session.restoreAndEnd(panels);
        sessions.remove(animator);
        if (composeAdapter != null) {
            adapterSessions.remove(composeAdapter);
        }

        if (restoreComposeSlideFraction && composeAdapter != null) {
            float target = (transitionState == HorizontalTransitionMath.QS_STATE
                    || transitionState == HorizontalTransitionMath.SHADE_STATE)
                    ? HorizontalTransitionMath.settledSlideFraction(transitionState)
                    : (priorSlideFraction != null
                            ? priorSlideFraction
                            : HorizontalTransitionMath.COMPOSE_SLIDE_FRACTION_VISIBLE);
            writeComposeSlideFractionInternal(composeAdapter, slideFractionFlow, target);
        }
    }

    private void fail(Throwable error) {
        if (!failed) {
            failed = true;
            activeSessionCount = 0;
            ColdGpuPipelineWarmup.markHorizontalGesture(false);
            for (HorizontalSwipeSession session : sessions.values()) {
                if (session != null && session.isActive()) {
                    try {
                        session.restoreAndEnd(panels);
                    } catch (Throwable ignored) {
                    }
                }
            }
            sessions.clear();
            adapterSessions.clear();
            module.warn("Horizontal panel transition hooks disabled until SystemUI restarts: unexpected contract failure", error);
        }
    }

    private static boolean mainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }
}
