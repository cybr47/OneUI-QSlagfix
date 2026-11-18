package com.samsung.music.qs.lagfix;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Resolves all SystemUI classes, fields, methods, and enum constants once at module startup and
 * provides zero-lookup per-frame accessors for {@link HorizontalPanelTransitionHooks}.
 */
final class HorizontalTransitionPanels implements HorizontalSwipeSession.PropertyAccess<View> {
    final Method slide;
    final Method onTransitionStateChanged;
    final Method clearAnimationState;
    final Method onPanelClosed;
    final Method setSlideFraction;
    final Method setBlurAlphaFromParent;

    private final Method isThereNoView;
    private final Method animViewGetView;
    private final Method animViewSetAlpha;
    private final Method animViewSetTranslationX;
    private final Method animViewSetTranslationY;
    private final Method animViewSetScaleX;
    private final Method animViewSetScaleY;
    private final Method animViewSetVisibility;
    private final Method helperIsReversed;
    private final Method helperUpdatePanelVisibility;
    private final Method lazyGetValue;
    private final Method stateFlowGetValue;

    private final Field panelTransitionState;
    private final Field splitEnabled;
    private final Field baseSplitEnabled;
    private final Field baseUserChanged;
    private final Field qsRootView;
    private final Field shadeRootView;
    private final Field header;
    private final Field buttonContainer;
    private final Field clockDateContainer;
    private final Field plmn;
    private final Field qsButtonsContainer;
    private final Field composeAdapter;
    private final Field secPanelSplitHelperLazy;

    private final Field animViewField;
    private final Field adapterSlideFractionFlow;

    private final Field helperEnabled;
    private final Field helperCurrentState;
    private final Field helperPanelRootView;
    private final Field helperComposeAdapter;

    private final Field eventState;
    private final Field eventFraction;

    private final Object leftDirection;
    private final Object rightDirection;
    private final Class<?> composeViewType;

    HorizontalTransitionPanels(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> animatorType = Class.forName(
                "com.android.systemui.qs.animator.PanelTransitionAnimator", false, loader);
        Class<?> animatorBaseType = Class.forName(
                "com.android.systemui.qs.animator.SecQSImplAnimatorBase", false, loader);
        Class<?> animViewType = Class.forName(
                "com.android.systemui.qs.animator.QSAnimView", false, loader);
        Class<?> composeAdapterType = Class.forName(
                "com.android.systemui.samsung.quicksetting.SecQSPanelComposeAdapter", false, loader);
        Class<?> helperType = Class.forName(
                "com.android.systemui.shade.SecPanelSplitHelper", false, loader);
        Class<?> directionType = Class.forName(
                "com.android.systemui.shade.PanelSlideEventHandler$Direction", false, loader);
        Class<?> eventType = Class.forName(
                "com.android.systemui.shade.PanelTransitionStateChangeEvent", false, loader);
        Class<?> activatableNotificationViewType = optionalClass(
                "com.android.systemui.statusbar.notification.row.ActivatableNotificationView", loader);
        Class<?> lazyType = Class.forName("kotlin.Lazy", false, loader);
        Class<?> stateFlowType = Class.forName("kotlinx.coroutines.flow.StateFlow", false, loader);

        slide = method(animatorType, "slide", float.class, float.class, directionType, int.class);
        onTransitionStateChanged = method(animatorType, "onPanelTransitionStateChanged", eventType);
        clearAnimationState = method(animatorType, "clearAnimationState");
        onPanelClosed = optionalMethod(animatorType, "onPanelClosed$1");
        setSlideFraction = method(composeAdapterType, "setSlideFraction", float.class);
        setBlurAlphaFromParent = activatableNotificationViewType != null
                ? optionalMethod(activatableNotificationViewType, "setBlurAlphaFromParent", float.class)
                : null;

        isThereNoView = method(animatorType, "isThereNoView");
        animViewGetView = method(animViewType, "getView");
        animViewSetAlpha = method(animViewType, "setAlpha", float.class);
        animViewSetTranslationX = method(animViewType, "setTranslationX", float.class);
        animViewSetTranslationY = method(animViewType, "setTranslationY", float.class);
        animViewSetScaleX = method(animViewType, "setScaleX", float.class);
        animViewSetScaleY = method(animViewType, "setScaleY", float.class);
        animViewSetVisibility = method(animViewType, "setVisibility", int.class);

        helperIsReversed = method(helperType, "isReversed");
        helperUpdatePanelVisibility = method(helperType, "updatePanelVisibility");
        lazyGetValue = method(lazyType, "getValue");
        stateFlowGetValue = method(stateFlowType, "getValue");

        panelTransitionState = field(animatorType, "panelTransitionState");
        splitEnabled = field(animatorType, "splitEnabled");
        baseSplitEnabled = field(animatorBaseType, "mPanelSplitEnabled");
        baseUserChanged = field(animatorBaseType, "mUserChanged");
        qsRootView = field(animatorType, "qsRootView");
        shadeRootView = field(animatorType, "shadeRootView");
        header = field(animatorType, "header");
        buttonContainer = field(animatorType, "buttonContainer");
        clockDateContainer = field(animatorType, "clockDateContainer");
        plmn = field(animatorType, "plmn");
        qsButtonsContainer = field(animatorType, "qsButtonsContainer");
        composeAdapter = field(animatorType, "composeAdapter");
        secPanelSplitHelperLazy = field(animatorType, "secPanelSplitHelper$delegate");

        animViewField = field(animViewType, "view");
        adapterSlideFractionFlow = field(composeAdapterType, "_slideFraction");

        helperEnabled = field(helperType, "enabled");
        helperCurrentState = field(helperType, "currentState");
        helperPanelRootView = field(helperType, "panelRootView");
        helperComposeAdapter = field(helperType, "composeAdapter");

        eventState = field(eventType, "state");
        eventFraction = field(eventType, "fraction");

        leftDirection = field(directionType, "LEFT").get(null);
        rightDirection = field(directionType, "RIGHT").get(null);
        composeViewType = optionalClass("androidx.compose.ui.platform.ComposeView", loader);
    }

    boolean isLeft(Object direction) {
        return direction != null && direction == leftDirection;
    }

    boolean isRight(Object direction) {
        return direction != null && direction == rightDirection;
    }

    String directionName(Object direction) {
        if (direction == leftDirection) {
            return HorizontalTransitionMath.DIRECTION_LEFT;
        }
        if (direction == rightDirection) {
            return HorizontalTransitionMath.DIRECTION_RIGHT;
        }
        return null;
    }

    int getTransitionState(Object animator) throws IllegalAccessException {
        return panelTransitionState.getInt(animator);
    }

    boolean isSplitEnabled(Object animator) throws IllegalAccessException {
        return splitEnabled.getBoolean(animator) || baseSplitEnabled.getBoolean(animator);
    }

    boolean isUserChanged(Object animator) throws IllegalAccessException {
        return baseUserChanged.getBoolean(animator);
    }

    boolean isThereNoView(Object animator) throws ReflectiveOperationException {
        return (boolean) isThereNoView.invoke(animator);
    }

    int getEventState(Object event) throws IllegalAccessException {
        return eventState.getInt(event);
    }

    float getEventFraction(Object event) throws IllegalAccessException {
        return eventFraction.getFloat(event);
    }

    Object getComposeAdapter(Object animator) throws IllegalAccessException {
        return composeAdapter.get(animator);
    }

    /**
     * Resolves and caches all per-animator object and view references into {@code session} once
     * when a horizontal swipe starts so subsequent drag frames perform zero reflection lookups.
     */
    void bindSession(Object animator, HorizontalSwipeSession session) throws ReflectiveOperationException {
        Object lazy = secPanelSplitHelperLazy.get(animator);
        Object helper = lazy != null ? lazyGetValue.invoke(lazy) : null;
        session.splitHelper = helper;

        Object adapter = composeAdapter.get(animator);
        if (adapter == null && helper != null) {
            adapter = helperComposeAdapter.get(helper);
        }
        session.composeAdapter = adapter;
        session.slideFractionFlow = adapter != null ? adapterSlideFractionFlow.get(adapter) : null;
        session.panelRootView = helper != null ? (View) helperPanelRootView.get(helper) : null;
        session.reversed = helper != null && (boolean) helperIsReversed.invoke(helper);

        session.qsAnimView = qsRootView.get(animator);
        session.shadeAnimView = shadeRootView.get(animator);
        session.buttonAnimView = buttonContainer.get(animator);
        session.clockDateAnimView = clockDateContainer.get(animator);
        session.headerAnimView = header.get(animator);
        session.plmnAnimView = plmn.get(animator);

        session.qsRootView = unwrapAnimView(session.qsAnimView);
        session.shadeRootView = unwrapAnimView(session.shadeAnimView);
        session.buttonView = unwrapAnimView(session.buttonAnimView);
        session.clockDateView = unwrapAnimView(session.clockDateAnimView);
        session.headerView = unwrapAnimView(session.headerAnimView);
        session.plmnView = unwrapAnimView(session.plmnAnimView);
        session.qsButtonsContainer = (View) qsButtonsContainer.get(animator);
    }

    private View unwrapAnimView(Object animView) throws ReflectiveOperationException {
        if (animView == null) {
            return null;
        }
        if (animView instanceof View view) {
            return view;
        }
        View view = (View) animViewField.get(animView);
        if (view != null) {
            return view;
        }
        return (View) animViewGetView.invoke(animView);
    }

    float resolvePanelWidth(HorizontalSwipeSession session) {
        int width = 0;
        if (session.panelRootView != null) {
            width = session.panelRootView.getWidth();
        }
        if (width <= 0 && session.qsRootView != null) {
            ViewParent parent = session.qsRootView.getParent();
            if (parent instanceof View parentView) {
                width = parentView.getWidth();
            }
            if (width <= 0) {
                width = session.qsRootView.getWidth();
            }
        }
        if (width <= 0 && session.shadeRootView != null) {
            width = session.shadeRootView.getWidth();
        }
        if (width <= 0 && session.qsRootView != null && session.qsRootView.getResources() != null) {
            width = session.qsRootView.getResources().getDisplayMetrics().widthPixels;
        }
        return (float) Math.max(width, 0);
    }

    Float readComposeSlideFraction(Object slideFractionFlow) throws ReflectiveOperationException {
        if (slideFractionFlow == null) {
            return null;
        }
        Object value = stateFlowGetValue.invoke(slideFractionFlow);
        return value instanceof Number number ? number.floatValue() : null;
    }

    void writeComposeSlideFractionIfNeeded(
            Object adapter, Object slideFractionFlow, float targetFraction)
            throws ReflectiveOperationException {
        if (adapter == null) {
            return;
        }
        Float current = readComposeSlideFraction(slideFractionFlow);
        if (current != null && Math.abs(current - targetFraction) < 0.0005f) {
            return;
        }
        setSlideFraction.invoke(adapter, targetFraction);
    }

    void invokeClearAnimationState(Object animator) throws ReflectiveOperationException {
        clearAnimationState.invoke(animator);
    }

    void invokeUpdatePanelVisibility(Object splitHelper) throws ReflectiveOperationException {
        if (splitHelper != null) {
            helperUpdatePanelVisibility.invoke(splitHelper);
        }
    }

    /**
     * Ensures ancestor views up to {@code stopBefore} and {@code ComposeView} children inside
     * {@code qsRootView} are drawable, saving their exact prior values in {@code session} before
     * modifying any property.
     */
    void prepareDrawables(HorizontalSwipeSession session) {
        if (session.drawablesPrepared) {
            return;
        }
        ensureAncestorsDrawable(session, session.qsRootView, session.panelRootView);
        ensureComposeChildrenDrawable(session, session.qsRootView);
        ensureAncestorsDrawable(session, session.shadeRootView, session.panelRootView);
        session.drawablesPrepared = true;
    }

    private void ensureAncestorsDrawable(
            HorizontalSwipeSession session, View view, View stopBefore) {
        if (view == null) {
            return;
        }
        ViewParent parent = view.getParent();
        int depth = 0;
        while (parent instanceof View parentView && parentView != stopBefore && depth < 8) {
            session.ensureDrawable(parentView, this);
            parent = parentView.getParent();
            depth++;
        }
    }

    private void ensureComposeChildrenDrawable(HorizontalSwipeSession session, View qsRootView) {
        if (!(qsRootView instanceof ViewGroup group)) {
            return;
        }
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(i);
            if (child == null) {
                continue;
            }
            session.ensureDrawable(child, this);
            if (((composeViewType != null && composeViewType.isInstance(child))
                    || "androidx.compose.ui.platform.ComposeView".equals(child.getClass().getName()))
                    && child instanceof ViewGroup composeGroup) {
                for (int j = 0; j < composeGroup.getChildCount(); j++) {
                    View inner = composeGroup.getChildAt(j);
                    if (inner != null) {
                        session.ensureDrawable(inner, this);
                    }
                }
            }
        }
    }

    @Override
    public int getVisibility(View target) {
        return target.getVisibility();
    }

    @Override
    public void setVisibility(View target, Object animWrapper, int visibility) {
        if (animWrapper != null) {
            try {
                animViewSetVisibility.invoke(animWrapper, visibility);
                return;
            } catch (Throwable ignored) {
            }
        }
        target.setVisibility(visibility);
    }

    @Override
    public float getAlpha(View target) {
        return target.getAlpha();
    }

    @Override
    public void setAlpha(View target, Object animWrapper, float alpha) {
        if (animWrapper != null) {
            try {
                animViewSetAlpha.invoke(animWrapper, alpha);
                return;
            } catch (Throwable ignored) {
            }
        }
        target.setAlpha(alpha);
    }

    @Override
    public float getTranslationX(View target) {
        return target.getTranslationX();
    }

    @Override
    public void setTranslationX(View target, Object animWrapper, float translationX) {
        if (animWrapper != null) {
            try {
                animViewSetTranslationX.invoke(animWrapper, translationX);
                return;
            } catch (Throwable ignored) {
            }
        }
        target.setTranslationX(translationX);
    }

    @Override
    public float getTranslationY(View target) {
        return target.getTranslationY();
    }

    @Override
    public void setTranslationY(View target, Object animWrapper, float translationY) {
        if (animWrapper != null) {
            try {
                animViewSetTranslationY.invoke(animWrapper, translationY);
                return;
            } catch (Throwable ignored) {
            }
        }
        target.setTranslationY(translationY);
    }

    @Override
    public float getScaleX(View target) {
        return target.getScaleX();
    }

    @Override
    public void setScaleX(View target, Object animWrapper, float scaleX) {
        if (animWrapper != null) {
            try {
                animViewSetScaleX.invoke(animWrapper, scaleX);
                return;
            } catch (Throwable ignored) {
            }
        }
        target.setScaleX(scaleX);
    }

    @Override
    public float getScaleY(View target) {
        return target.getScaleY();
    }

    @Override
    public void setScaleY(View target, Object animWrapper, float scaleY) {
        if (animWrapper != null) {
            try {
                animViewSetScaleY.invoke(animWrapper, scaleY);
                return;
            } catch (Throwable ignored) {
            }
        }
        target.setScaleY(scaleY);
    }

    private static Class<?> optionalClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field value = type.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static Method method(Class<?> type, String name, Class<?>... args)
            throws NoSuchMethodException {
        Method value = type.getDeclaredMethod(name, args);
        value.setAccessible(true);
        return value;
    }

    private static Method optionalMethod(Class<?> type, String name, Class<?>... args) {
        try {
            return method(type, name, args);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }
}
