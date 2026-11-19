package com.samsung.music.qs.lagfix;

import android.os.Looper;
import android.view.View;
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
                activeSessionCount++;
                panels.bindSession(animator, session);
                session.cachePanelAncestry();
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

            // The old FrameTransform.forSlide() allocated one object per
            // animation callback and calculated four translation/alpha fields
            // which this fade-only renderer never used. Keep its exact
            // horizontal-state guard and direction/fraction math.
            if (!HorizontalTransitionMath.isHorizontalDirection(directionName)) {
                return chain.proceed();
            }
            boolean movingToQs = HorizontalTransitionMath.isTransitionToQs(
                    stateToChange, directionName, session.reversed);
            float qsShown = HorizontalTransitionMath.qsShownFraction(
                    fraction, movingToQs);

            pinComposeSlideFractionOnce(session);
            panels.prepareDrawables(session);
            applyGlassFadeFrame(session, qsShown, width);
            if (observedHorizontalFrames++ < 3) {
                module.note("Glass fade frame applied; fraction=" + fraction
                        + " toQs=" + movingToQs + " width=" + width);
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
        if (!session.buttonNestedInShade) {
            session.applyPageTransform(session.buttonView, session.buttonAnimView,
                    notiX, notificationAlpha, true, panels);
        }
        if (!session.clockNestedInShade) {
            session.applyPageTransform(session.clockDateView, session.clockDateAnimView,
                    notiX, notificationAlpha, true, panels);
        }
        session.keepStationaryX(session.headerView, session.headerAnimView, panels);
        session.keepStationaryX(session.plmnView, session.plmnAnimView, panels);
        session.keepStationaryX(session.qsButtonsContainer, null, panels);
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
