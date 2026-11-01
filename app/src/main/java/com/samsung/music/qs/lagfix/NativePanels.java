package com.samsung.music.qs.lagfix;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

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
    private final Method createSlide, updateResource;
    private final Field maxDrag, flingUtils, minFlingVelocity;
    private final Object undecided, down;
    private final WeakHashMap<View, HeaderArea> headerAreas = new WeakHashMap<>();
    private boolean headerResourcesResolved;
    private int dateButtonsId, buttonsHeightId, dateBottomPaddingId;

    private static final class HeaderArea {
        final int width, height;
        final float bottom;

        HeaderArea(View panel, float bottom) {
            width = panel.getWidth();
            height = panel.getHeight();
            this.bottom = bottom;
        }
    }

    /**
     * Release velocity (px/s) handed to Samsung's own slide animator. It only needs to exceed the
     * animator's minimum fling velocity so the switch always commits; Samsung's FlingAnimationUtils
     * then derives the duration and interpolator exactly as for a real finger fling.
     */
    private static final float COMMIT_VELOCITY_PX_PER_SECOND = 4000f;

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
        // Samsung's own finger-release animator: createSlideAnimatorAndRun(velocity, draggedDistance).
        createSlide = method(handlerType, "createSlideAnimatorAndRun", float.class, float.class);
        updateResource = method(handlerType, "updateResource");
        maxDrag = field(handlerType, "maxDragWidth");
        Class<?> flingType = Class.forName("com.android.wm.shell.animation.FlingAnimationUtils", false, loader);
        flingUtils = field(handlerType, "flingAnimationUtils");
        if (flingUtils.getType() != flingType) throw new NoSuchFieldException("flingAnimationUtils type");
        minFlingVelocity = field(flingType, "mMinVelocityPxPerSecond");
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
        down = field(directionType, "DOWN").get(null);
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

    String debugState(Object helper) {
        if (helper == null) return "helper=null";
        try {
            View root = (View) panelRoot.get(helper);
            return "helper=present; enabled=" + enabled.getBoolean(helper)
                    + "; expanded=" + expanded.getBoolean(helper)
                    + "; state=" + current.getInt(helper)
                    + "; status=" + status.getInt(null)
                    + "; popOver=" + popOver.invoke(mode.get(helper))
                    + "; qsDisabled=" + qsDisabled.getBoolean(helper)
                    + "; shadeDisabled=" + shadeDisabled.getBoolean(helper)
                    + "; detailOrCustomizer=" + blocked()
                    + "; panelRoot=" + (root == null ? "null"
                            : root.getWidth() + "x" + root.getHeight()
                                    + ",attached=" + root.isAttachedToWindow());
        } catch (Throwable error) {
            return "snapshot failed: " + error;
        }
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
        area.getLocationOnScreen(location);
        int panelTop = location[1];
        float x = event.getRawX() - location[0];
        float y = event.getRawY() - panelTop;
        if (x < 0 || x >= area.getWidth() || y < 0 || y >= area.getHeight()) return false;

        View window = area.getRootView();
        float bottom = existingHeader.getMeasuredHeight();
        if (current.getInt(helper) == PanelSwitch.TRANSITION) {
            // Moving headers must not temporarily turn the notification/tile body into a swipe area.
            HeaderArea previous = headerAreas.get(window);
            if (previous != null && previous.width == area.getWidth() && previous.height == area.getHeight()) {
                bottom = Math.max(bottom, previous.bottom);
            }
        } else {
            bottom = Math.max(bottom, dateRowBottom(window, area.getResources(), panelTop, location));
            bottom = Math.min(bottom, area.getHeight());
            headerAreas.put(window, new HeaderArea(area, bottom));
        }
        return y < bottom;
    }

    private float dateRowBottom(View window, Resources resources, int panelTop, int[] location) {
        if (!headerResourcesResolved) {
            dateButtonsId = resources.getIdentifier("quick_qs_date_buttons", "id", "com.android.systemui");
            buttonsHeightId = resources.getIdentifier("sec_qs_buttons_container_height", "dimen", "com.android.systemui");
            dateBottomPaddingId = resources.getIdentifier("date_text_bottom_padding", "dimen", "com.android.systemui");
            headerResourcesResolved = true;
        }
        Rect visible = new Rect();
        float bottom = 0f;
        // The notification/legacy header exposes the whole clock/date/button row as a native view.
        View dateButtons = dateButtonsId == 0 ? null : window.findViewById(dateButtonsId);
        if (visible(dateButtons, visible)) {
            dateButtons.getLocationOnScreen(location);
            bottom = location[1] + dateButtons.getHeight() - panelTop;
        }

        // One UI 9's QS header is Compose; these two AndroidView tags are set by Samsung itself.
        View clock = window.findViewWithTag("compose_header_clock");
        View date = window.findViewWithTag("compose_header_date");
        boolean clockVisible = visible(clock, visible);
        boolean dateVisible = visible(date, visible);
        float dateBottom = 0f;
        if (dateVisible) {
            date.getLocationOnScreen(location);
            dateBottom = location[1] + date.getHeight() - panelTop;
            bottom = Math.max(bottom, dateBottom);
        }
        if (clockVisible && buttonsHeightId != 0) {
            clock.getLocationOnScreen(location);
            // HeaderContainer centers its clock/date Row in sec_qs_buttons_container_height.
            // The clock has 1dp top padding; the bottom-aligned date has date_text_bottom_padding.
            float rowTop = location[1] - panelTop - resources.getDisplayMetrics().density;
            float rowBottom = location[1] + clock.getHeight() - panelTop;
            if (dateVisible && dateBottomPaddingId != 0) {
                rowBottom = Math.max(rowBottom, dateBottom + resources.getDimensionPixelSize(dateBottomPaddingId));
            }
            float containerBottom = (rowTop + rowBottom
                    + resources.getDimensionPixelSize(buttonsHeightId)) / 2f;
            bottom = Math.max(bottom, Math.max(rowBottom, containerBottom));
        }
        return bottom;
    }

    private static boolean visible(View view, Rect bounds) {
        return view != null && view.isShown() && view.isAttachedToWindow()
                && view.getAlpha() > 0f && view.getWidth() > 0 && view.getHeight() > 0
                && view.getGlobalVisibleRect(bounds);
    }

    void clearHeaderArea(View root) {
        headerAreas.remove(root.getRootView());
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
            @Override public void animate(int destination) throws ReflectiveOperationException {
                animateState(helper, destination);
            }
        };
    }

    /**
     * Cancels any running native slide animator and clears every finger-tracking flag, leaving the
     * handler as it is after a finished gesture. Must run on the UI thread.
     */
    private void quiesce(Object helper, Object h) throws ReflectiveOperationException {
        ValueAnimator animator = (ValueAnimator) sliderAnimator.get(h);
        if (animator != null) animator.cancel(); // Native listeners clear their animator and isSliding.
        sliderAnimator.set(h, null);
        tracking.setBoolean(h, false);
        intercepted.setBoolean(h, false);
        initialized.setBoolean(h, false);
        fullyOnDown.setBoolean(h, false);
        pointer.setInt(h, -1);
        ((VelocityTracker) velocity.get(h)).clear();
        onceOver.setBoolean(helper, false);
        overSlide.setFloat(helper, 0f);
        sliding.setBoolean(null, false);
    }

    /**
     * Starts Samsung's own animated panel switch: the leaving panel fades out towards the bottom and the
     * arriving panel fades in from the top. This is exactly what a finger-released vertical pull does:
     * Samsung's {@code createSlideAnimatorAndRun} drives {@code SecPanelSplitHelper.slide(value, DOWN, ..)}
     * on every frame, which feeds {@code SecQSImplAnimatorManager.slide(..)} and finally commits the
     * terminal state itself. Nothing here draws, schedules or animates anything.
     */
    private void animateState(Object helper, int destination) throws ReflectiveOperationException {
        if (!PanelSwitch.terminal(destination)) throw new IllegalArgumentException("non-panel state");
        Object h = handler.get(helper);
        int source = current.getInt(helper);
        if (!PanelSwitch.terminal(source)) source = destination ^ 1; // settle() makes this unreachable.
        quiesce(helper, h);
        // The same fields Samsung holds at the moment a downward header pull is released.
        start.setInt(helper, source);
        target.setInt(helper, destination);
        direction.set(h, down);
        if (maxDrag.getFloat(h) <= 0f) updateResource.invoke(h); // Normally set in the constructor.
        float minVelocity = minFlingVelocity.getFloat(flingUtils.get(h));
        float commitVelocity = Math.max(COMMIT_VELOCITY_PX_PER_SECOND, minVelocity * 4f);
        // Start just past the touch slop, where a real pull would be classified as a downward slide.
        float distance = Math.max(touchSlop.getInt(h), 1);
        createSlide.invoke(h, commitVelocity, distance);
        // ValueAnimator.start() delivers the first frame synchronously, so Samsung's slide() has already
        // either moved the panels into TRANSITION, committed the destination (animations off), or refused.
        int after = current.getInt(helper);
        ValueAnimator animator = (ValueAnimator) sliderAnimator.get(h);
        if (after != PanelSwitch.TRANSITION && after != destination) {
            // Refused (disabled panel, detail screen...): leave nothing running and the state untouched.
            if (animator != null) animator.cancel();
            sliderAnimator.set(h, null);
            direction.set(h, undecided);
            sliding.setBoolean(null, false);
            return;
        }
        if (animator != null) {
            animator.addListener(new SettleListener(helper, destination));
        } else if (after == PanelSwitch.TRANSITION) {
            // The animation already ended without committing: finish the switch ourselves.
            switchState.invoke(helper, destination);
        }
    }

    /** Guards against a stuck half-transition if Samsung's final frame did not commit the state. */
    private final class SettleListener extends AnimatorListenerAdapter {
        private final Object helper;
        private final int destination;
        private boolean cancelled;

        SettleListener(Object helper, int destination) {
            this.helper = helper;
            this.destination = destination;
        }

        @Override public void onAnimationCancel(Animator animation) {
            cancelled = true;
        }

        @Override public void onAnimationEnd(Animator animation) {
            if (cancelled) return;
            try {
                if (current.getInt(helper) == PanelSwitch.TRANSITION) switchState.invoke(helper, destination);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // The next swipe settles any remaining transition through PanelSwitch.settle().
            }
        }
    }

    /** Instantly completes a switch with no animation; used only to settle an in-flight transition. */
    private void finishState(Object helper, int destination) throws ReflectiveOperationException {
        if (!PanelSwitch.terminal(destination)) throw new IllegalArgumentException("non-panel state");
        Object h = handler.get(helper);
        int source = current.getInt(helper);
        if (!PanelSwitch.terminal(source)) source = start.getInt(helper);
        if (!PanelSwitch.terminal(source)) source = destination ^ 1;
        quiesce(helper, h);
        direction.set(h, undecided);
        start.setInt(helper, source);
        target.setInt(helper, destination);
        fraction.invoke(helper, 0f);
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
