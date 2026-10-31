package com.samsung.music.qs.lagfix;

import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Cached reflection contracts verified in the supplied SystemUI APK; no name alternatives. */
final class NativePanels {
    final Class<?> rootType, helperType, directionType;
    final Method dispatch, intercept, slide, rootDetached, rootConfig;
    private final Field interaction, controller, stackController, splitHelper;
    private final Field enabled, expanded, current, start, target, handler, panelRoot;
    private final Field qsDisabled, shadeDisabled, mode, onceOver, overSlide;
    private final Field sliderAnimator, tracking, intercepted, initialized, pointer, fullyOnDown;
    private final Field velocity, direction, touchSlop, headerLazy, header;
    private final Field status, sliding, detailOpening, detailShowing, detailClosing, customizing;
    private final Method lazyValue, popOver, fraction, switchState;
    private final Object undecided;

    NativePanels(ClassLoader loader) throws ReflectiveOperationException {
        rootType = Class.forName("com.android.systemui.shade.NotificationShadeWindowView", false, loader);
        helperType = Class.forName("com.android.systemui.shade.SecPanelSplitHelper", false, loader);
        Class<?> handlerType = Class.forName("com.android.systemui.shade.PanelSlideEventHandler", false, loader);
        directionType = Class.forName("com.android.systemui.shade.PanelSlideEventHandler$Direction", false, loader);
        Class<?> animatorState = Class.forName("com.android.systemui.qs.animator.QsAnimatorState", false, loader);
        dispatch = method(rootType, "dispatchTouchEvent", MotionEvent.class);
        intercept = method(helperType, "onIntercept", MotionEvent.class);
        slide = method(helperType, "slide", float.class, directionType, boolean.class);
        rootDetached = method(rootType, "onDetachedFromWindow");
        rootConfig = method(rootType, "onConfigurationChanged", android.content.res.Configuration.class);
        interaction = field(rootType, "mInteractionEventHandler");
        controller = field(interaction.getType(), "this$0");
        stackController = field(controller.getType(), "mNotificationStackScrollLayoutController");
        splitHelper = field(stackController.getType(), "mPanelSplitHelper");
        if (splitHelper.getType() != helperType) throw new NoSuchFieldException("mPanelSplitHelper type");
        enabled = field(helperType, "enabled");
        expanded = field(helperType, "panelExpanded");
        current = field(helperType, "currentState");
        start = field(helperType, "stateOnDown");
        target = field(helperType, "stateToChange");
        handler = field(helperType, "panelSlideEventHandler");
        panelRoot = field(helperType, "panelRootView");
        qsDisabled = field(helperType, "isQsDisabled");
        shadeDisabled = field(helperType, "isShadeDisabled");
        mode = field(helperType, "secQsUiDisplayModeInteractor");
        onceOver = field(helperType, "onceOverSlide");
        overSlide = field(helperType, "overSlideAmount");
        popOver = method(mode.getType(), "isPopOverEnabled");
        fraction = method(helperType, "setDraggedFraction", float.class);
        switchState = method(helperType, "slide$1", int.class);
        sliderAnimator = field(handlerType, "sliderAnimator");
        tracking = field(handlerType, "tracking");
        intercepted = field(handlerType, "panelSliderIntercepted");
        initialized = field(handlerType, "slidingInitialized");
        pointer = field(handlerType, "activePointerId");
        fullyOnDown = field(handlerType, "fullyExpandedOnDown");
        velocity = field(handlerType, "velocityTracker");
        direction = field(handlerType, "direction");
        touchSlop = field(handlerType, "touchSlop");
        headerLazy = field(handlerType, "shadeHeaderController$delegate");
        Class<?> lazy = Class.forName("kotlin.Lazy", false, loader);
        lazyValue = method(lazy, "getValue");
        Class<?> headerType = Class.forName("com.android.systemui.shade.ShadeHeaderController", false, loader);
        header = field(headerType, "header");
        status = field(animatorState, "state");
        sliding = field(animatorState, "isSliding");
        detailOpening = field(animatorState, "isDetailOpening");
        detailShowing = field(animatorState, "isDetailShowing");
        detailClosing = field(animatorState, "isDetailClosing");
        customizing = field(animatorState, "isCustomizerShowing");
        undecided = field(directionType, "UNDECIDED").get(null);
    }

    Object helper(Object root) throws IllegalAccessException {
        Object eventHandler = interaction.get(root);
        if (eventHandler == null) return null;
        Object owner = controller.get(eventHandler);
        if (owner == null) return null;
        Object stack = stackController.get(owner);
        return stack == null ? null : splitHelper.get(stack);
    }

    boolean enabled(Object helper) throws ReflectiveOperationException {
        // Preserve native keyguard/falsing, combined mode, tablet pop-over and disabled-panel policies.
        return helper != null && enabled.getBoolean(helper) && status.getInt(null) == 0
                && !qsDisabled.getBoolean(helper) && !shadeDisabled.getBoolean(helper)
                && !(boolean) popOver.invoke(mode.get(helper));
    }

    boolean available(Object helper) throws ReflectiveOperationException {
        if (!enabled(helper) || !expanded.getBoolean(helper) || blocked()) return false;
        int state = current.getInt(helper);
        View root = (View) panelRoot.get(helper);
        return (PanelSwitch.terminal(state) || state == PanelSwitch.TRANSITION)
                && root != null && root.isAttachedToWindow();
    }

    private boolean blocked() throws IllegalAccessException {
        return detailOpening.getBoolean(null) || detailShowing.getBoolean(null)
                || detailClosing.getBoolean(null) || customizing.getBoolean(null);
    }

    boolean inHeader(Object helper, MotionEvent event, int[] location) throws ReflectiveOperationException {
        View area = (View) panelRoot.get(helper);
        if (area == null) return false;
        Object h = handler.get(helper);
        View existingHeader = (View) header.get(lazyValue.invoke(headerLazy.get(h)));
        // Exactly Samsung's measured-height boundary, translated to the panel's coordinate space.
        area.getLocationOnScreen(location);
        float x = event.getRawX() - location[0];
        float y = event.getRawY() - location[1];
        return existingHeader.getMeasuredHeight() > 0 && x >= 0 && x < area.getWidth()
                && y >= 0 && y < existingHeader.getMeasuredHeight();
    }

    float slop(Object helper) throws IllegalAccessException {
        return touchSlop.getInt(handler.get(helper));
    }

    PanelSwitch.Access access(Object helper) {
        return new PanelSwitch.Access() {
            @Override public int current() throws IllegalAccessException {
                return NativePanels.this.current.getInt(helper);
            }
            @Override public int transitionTarget() throws IllegalAccessException {
                return target.getInt(helper);
            }
            @Override public void finish(int destination) throws ReflectiveOperationException {
                finishState(helper, destination);
            }
        };
    }

    private void finishState(Object helper, int destination) throws ReflectiveOperationException {
        if (!PanelSwitch.terminal(destination)) throw new IllegalArgumentException("non-panel state");
        Object h = handler.get(helper);
        int source = current.getInt(helper);
        if (!PanelSwitch.terminal(source)) source = start.getInt(helper);
        if (!PanelSwitch.terminal(source)) source = destination ^ 1;
        ValueAnimator animator = (ValueAnimator) sliderAnimator.get(h);
        if (animator != null) animator.cancel(); // Native listeners clear their animator and isSliding.
        sliderAnimator.set(h, null);
        tracking.setBoolean(h, false);
        intercepted.setBoolean(h, false);
        initialized.setBoolean(h, false);
        fullyOnDown.setBoolean(h, false);
        pointer.setInt(h, -1);
        direction.set(h, undecided);
        ((VelocityTracker) velocity.get(h)).clear();
        start.setInt(helper, source);
        target.setInt(helper, destination);
        onceOver.setBoolean(helper, false);
        overSlide.setFloat(helper, 0f);
        fraction.invoke(helper, 0f);
        sliding.setBoolean(null, false);
        // Samsung owns listener notification, repository state, visibility and terminal Compose state.
        switchState.invoke(helper, destination);
        int actual = current.getInt(helper);
        if (PanelSwitch.terminal(actual)) {
            start.setInt(helper, actual);
            target.setInt(helper, actual ^ 1);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field value = type.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        Method value = type.getDeclaredMethod(name, args);
        value.setAccessible(true);
        return value;
    }
}
