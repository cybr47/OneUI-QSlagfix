package com.samsung.music.qs.lagfix;

import android.os.Looper;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewParent;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedInterface;

/**
 * Installs the GlassUI-style horizontal transition between Notifications and Quick Settings on
 * the {@code cust} branch of ShadeSwitch using the libxposed 102
 * {@code module.hook(method).intercept(...)} API.
 *
 * <p>Key design guarantees:
 * <ol>
 *   <li><b>All reflection resolved once</b>: {@link HorizontalTransitionPanels} resolves every
 *       {@link Class}, {@link java.lang.reflect.Field}, {@link java.lang.reflect.Method}, and
 *       {@code Direction} enum constant once during
 *       {@link #install(SamsungMusicWavesHook, ClassLoader)}, and caches per-animator object/view
 *       references inside {@link HorizontalSwipeSession} when a swipe starts.
 *   <li><b>No double {@code slideFraction} writes per frame</b>: During an active horizontal
 *       transition ({@code panelTransitionState == TRANSITION_STATE}, {@code LEFT}/{@code RIGHT},
 *       {@code overSlideX == 0f}), {@link #interceptSlide} handles the frame directly without
 *       invoking stock {@code PanelTransitionAnimator.slide} (which would otherwise write
 *       {@code 1f - fraction} or {@code fraction} to {@code composeAdapter.setSlideFraction} and
 *       step stock's {@code ±300px}/{@code ±250px} {@code TouchAnimator}s). Instead,
 *       {@code setSlideFraction(0f)} is written at most once when the horizontal swipe begins.
 *   <li><b>Single-pass page fade across both Canvas content and window-blur card backgrounds</b>:
 *       <ul>
 *         <li>Pinning {@code SecQSPanelComposeAdapter.setSlideFraction(0f)} keeps Compose
 *             {@code alphaOfScene}, {@code alphaOfBlurTileBackground}, and {@code alphaOfSlide} at
 *             {@code 1.0f} so {@code qsRootView.setAlpha(frame.qsAlpha)} is the sole opacity curve
 *             on Quick Settings.
 *         <li>In One UI 9 ({@code NotiRune.NOTI_STYLE_ENR_WINDOW_BLUR}),
 *             {@code NotificationStackScrollLayout.setAlpha(v)} sets the {@code ViewGroup} layer
 *             alpha ({@code super.setAlpha(v)}) for Canvas-drawn text, icons, and media controls,
 *             and calls {@code ActivatableNotificationView.setBlurAlphaFromParent(v)} for each
 *             card's window-level {@code BackgroundBlurDrawable} (which does not inherit the
 *             {@code ViewGroup} Canvas layer alpha). {@link #interceptSetBlurAlphaFromParent}
 *             computes {@code parentAlpha * rowOwnAlpha} via
 *             {@link HorizontalTransitionMath#effectiveNotificationBlurAlpha(float, float, boolean, float)}
 *             so both ordinary notification cards and the large media card fade their rounded
 *             backgrounds in lockstep with their contents while preserving any existing row opacity.
 *         <li>Skipping redundant per-frame {@code onPanelTransitionStateChanged(TRANSITION_STATE)}
 *             callbacks (emitted by {@code SecPanelSplitHelper.setDraggedFraction} on each drag
 *             frame) prevents mid-drag visibility/alpha churn on {@code buttonContainer} and
 *             {@code clockDateContainer}.
 *       </ul>
 *   <li><b>Scoped cleanup &amp; exact prior-value restoration</b>: View mutations and cleanup are
 *       strictly scoped to an active {@link HorizontalSwipeSession}. When no horizontal swipe is
 *       active, {@code onPanelTransitionStateChanged}, {@code clearAnimationState}, and
 *       {@code onPanelClosed$1} pass through untouched so ordinary vertical shade and lockscreen
 *       animations are never disturbed. When an active horizontal swipe ends, every modified view
 *       (including ancestors and {@code ComposeView} children) is restored to its exact saved
 *       prior values before native settlement runs.
 * </ol>
 */
final class HorizontalPanelTransitionHooks {
    private final SamsungMusicWavesHook module;
    private final HorizontalTransitionPanels panels;
    private final WeakHashMap<Object, HorizontalSwipeSession> sessions = new WeakHashMap<>();
    private final WeakHashMap<Object, HorizontalSwipeSession> adapterSessions = new WeakHashMap<>();
    private int activeSessionCount;
    private boolean internalSlideFractionWrite;
    private volatile boolean failed;
    private final WeakHashMap<Object, GlassFrame> glassFrames = new WeakHashMap<>();
    private int observedHorizontalFrames;

    /**
     * One VSYNC-driven motion runner per live Samsung panel animator.
     * Samsung remains responsible for state and touch. We own only the visual
     * transforms; stock PanelTransitionAnimator.slide is skipped on this path.
     */
    private static final class GlassFrame implements Choreographer.FrameCallback {
        final HorizontalPanelTransitionHooks owner;
        final Object animator;
        final HorizontalSwipeSession session;
        final GlassMotionPhysics spring = new GlassMotionPhysics();
        boolean scheduled, cancelled, toQs;
        float width;
        float originalPivotX;
        int originalLayerType;
        boolean layerPromoted;
        int appliedFrames;

        GlassFrame(HorizontalPanelTransitionHooks owner, Object animator,
                   HorizontalSwipeSession session, boolean toQs, float width) {
            this.owner = owner;
            this.animator = animator;
            this.session = session;
            this.toQs = toQs;
            this.width = width;
            View sheet = session.qsRootView;
            if (sheet != null) {
                originalPivotX = sheet.getPivotX();
                originalLayerType = sheet.getLayerType();
                // Match GlassUI's sheet renderer: cache its translated page.
                if (originalLayerType != View.LAYER_TYPE_HARDWARE) {
                    sheet.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                    layerPromoted = true;
                }
                sheet.setPivotX(0f);
            }
            spring.begin(toQs, System.nanoTime());
        }

        void sample(float qsShown, boolean directionToQs, float newWidth) {
            toQs = directionToQs;
            width = newWidth;
            spring.target(qsShown, System.nanoTime());
            schedule();
        }

        void schedule() {
            if (cancelled || scheduled) return;
            scheduled = true;
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            scheduled = false;
            if (cancelled || !session.isActive() || owner.failed) return;
            try {
                spring.frame(frameTimeNanos);
                owner.applyGlassFrame(this);
                appliedFrames++;
                if (!spring.isSettled()) schedule();
            } catch (ReflectiveOperationException | RuntimeException e) {
                owner.fail(e);
            }
        }

        void cancel() {
            cancelled = true;
            if (scheduled) {
                Choreographer.getInstance().removeFrameCallback(this);
                scheduled = false;
            }
            spring.stop();
            View sheet = session.qsRootView;
            if (sheet != null) {
                sheet.setPivotX(originalPivotX);
                if (layerPromoted) sheet.setLayerType(originalLayerType, null);
            }
        }
    }

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
            module.note("GlassUI dual-spring horizontal visual engine installed"
                    + " (VSYNC follow+settle+tail, native Samsung panel views; no stock slide transforms)");
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

            // Samsung still supplies the finger-controlled target on every update.
            // It does NOT render the native stock animation: we use one dedicated
            // VSYNC spring driver for the real Samsung QS / notification views.
            pinComposeSlideFractionOnce(session);
            panels.prepareDrawables(session);
            GlassFrame running = glassFrames.get(animator);
            if (running == null) {
                boolean toQs = frame.movingToQs;
                running = new GlassFrame(this, animator, session, toQs, width);
                glassFrames.put(animator, running);
            }
            running.sample(frame.qsShown, frame.movingToQs, width);
            if (observedHorizontalFrames++ < 3) {
                module.note("Glass native horizontal target received; fraction="
                        + fraction + " toQs=" + frame.movingToQs
                        + " width=" + width);
            }
            // Skip Samsung's original 300px/250px TouchAnimator and Compose
            // fractions; GlassFrame owns the visible movement until completion.
            return null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            fail(error);
            return chain.proceed();
        }
    }

    private void applyGlassFrame(GlassFrame motion) throws ReflectiveOperationException {
        HorizontalSwipeSession session = motion.session;
        if (!session.isActive() || motion.width <= 0f) return;
        float qs = GlassMotionPhysics.clamp01(motion.spring.page());
        float tail = motion.spring.tail();
        float sign = session.reversed ? -1f : 1f;
        float qsX = sign * motion.width * (1f - qs);
        float notiX = -sign * motion.width * qs;

        // GlassUI SideSlide.applySheet: leading-edge stretch, vertical squash,
        // alpha=min(1, page*1.5), sideways full-panel translation.
        float stretch = GlassMotionPhysics.squash(qs, tail, motion.width);
        float qsAlpha = GlassMotionPhysics.alpha(qs);
        float notificationAlpha = GlassMotionPhysics.alpha(1f-qs);
        session.applyPageTransform(session.qsRootView, session.qsAnimView,
                qsX, qsAlpha, true, panels);
        session.saveIfAbsent(session.qsRootView, session.qsAnimView, panels);
        if (session.qsRootView.getScaleX() != 1f + stretch)
            panels.setScaleX(session.qsRootView, session.qsAnimView, 1f + stretch);
        if (session.qsRootView.getScaleY() != 1f - 0.25f * stretch)
            panels.setScaleY(session.qsRootView, session.qsAnimView, 1f - 0.25f * stretch);

        // The other physical Samsung page follows the same full-screen axis.
        // Keep controls on the native views, not on any GlassUI replacement UI.
        session.applyPageTransform(session.shadeRootView, session.shadeAnimView,
                notiX, notificationAlpha, true, panels);
        // A child already inherits its parent's transform. Do not apply a
        // second translation to Samsung clock/date or button children.
        if (!isDescendantOf(session.buttonView, session.shadeRootView)) {
            session.applyPageTransform(session.buttonView, session.buttonAnimView,
                    notiX, notificationAlpha, true, panels);
        }
        if (!isDescendantOf(session.clockDateView, session.shadeRootView)) {
            session.applyPageTransform(session.clockDateView, session.clockDateAnimView,
                    notiX, notificationAlpha, true, panels);
        }

        // Shared header chrome is never treated as a sliding page.
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
        GlassFrame motion = glassFrames.remove(animator);
        if (motion != null) motion.cancel();
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
            for (GlassFrame frame : glassFrames.values()) {
                if (frame != null) frame.cancel();
            }
            glassFrames.clear();
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
