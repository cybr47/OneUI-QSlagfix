package com.samsung.music.qs.lagfix;

import android.content.res.Resources;
import android.graphics.Rect;
import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.WeakHashMap;

/** Only the native start-area contract is widened; Samsung owns the complete drag and release. */
final class NativePanels {
    private static final int QS = 0;
    private static final int NOTIFICATIONS = 1;
    private static final int TRANSITION = 2;
    private static final int STATUS_SHADE = 0;
    private static final int STATUS_SHADE_LOCKED = 2;
    final Method intercept, touch, initiate, pointerDeltas, updateDirection, rootDetached, rootConfig;
    private final Field owner, enabled, expanded, current, handler, panelRoot;
    private final Field qsDisabled, shadeDisabled, mode, initialY, changeSpot, intercepted, direction;
    private final Field touchSlop, headerLazy, header;
    private final Field status, detailOpening, detailShowing, detailClosing, customizing;
    private final Method lazyValue, popOver;
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

    NativePanels(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> rootType = Class.forName("com.android.systemui.shade.NotificationShadeWindowView", false, loader);
        Class<?> helperType = Class.forName("com.android.systemui.shade.SecPanelSplitHelper", false, loader);
        Class<?> handlerType = Class.forName("com.android.systemui.shade.PanelSlideEventHandler", false, loader);
        Class<?> directionType = Class.forName("com.android.systemui.shade.PanelSlideEventHandler$Direction", false, loader);
        Class<?> animatorState = Class.forName("com.android.systemui.qs.animator.QsAnimatorState", false, loader);
        intercept = method(helperType, "onIntercept", MotionEvent.class);
        touch = method(helperType, "handleTouch", MotionEvent.class);
        initiate = method(handlerType, "initiateSlide", MotionEvent.class);
        pointerDeltas = method(handlerType, "slidePointerDeltas", MotionEvent.class);
        updateDirection = method(handlerType, "updateDirection", float.class, float.class);
        rootDetached = method(rootType, "onDetachedFromWindow");
        rootConfig = method(rootType, "onConfigurationChanged", android.content.res.Configuration.class);
        owner = field(handlerType, "secPanelSplitHelper");
        if (owner.getType() != helperType) throw new NoSuchFieldException("secPanelSplitHelper type");
        enabled = field(helperType, "enabled");
        expanded = field(helperType, "panelExpanded");
        current = field(helperType, "currentState");
        handler = field(helperType, "panelSlideEventHandler");
        panelRoot = field(helperType, "panelRootView");
        qsDisabled = field(helperType, "isQsDisabled");
        shadeDisabled = field(helperType, "isShadeDisabled");
        mode = field(helperType, "secQsUiDisplayModeInteractor");
        popOver = method(mode.getType(), "isPopOverEnabled");
        initialY = field(handlerType, "initialY");
        changeSpot = field(handlerType, "isInChangeSpotOnDown");
        intercepted = field(handlerType, "panelSliderIntercepted");
        direction = field(handlerType, "direction");
        touchSlop = field(handlerType, "touchSlop");
        headerLazy = field(handlerType, "shadeHeaderController$delegate");
        Class<?> lazy = Class.forName("kotlin.Lazy", false, loader);
        lazyValue = method(lazy, "getValue");
        Class<?> headerType = Class.forName("com.android.systemui.shade.ShadeHeaderController", false, loader);
        header = field(headerType, "header");
        status = field(animatorState, "state");
        detailOpening = field(animatorState, "isDetailOpening");
        detailShowing = field(animatorState, "isDetailShowing");
        detailClosing = field(animatorState, "isDetailClosing");
        customizing = field(animatorState, "isCustomizerShowing");
        undecided = field(directionType, "UNDECIDED").get(null);
        down = field(directionType, "DOWN").get(null);
    }

    Object helper(Object nativeHandler) throws IllegalAccessException {
        return owner.get(nativeHandler);
    }

    Object handler(Object helper) throws IllegalAccessException {
        return handler.get(helper);
    }

    boolean available(Object helper) throws ReflectiveOperationException {
        if (helper == null) return false;
        int barState = status.getInt(null);
        // Samsung supports both the unlocked shade and the expanded shade above the lockscreen.
        if (!enabled.getBoolean(helper) || !expanded.getBoolean(helper)
                || (barState != STATUS_SHADE && barState != STATUS_SHADE_LOCKED)
                || qsDisabled.getBoolean(helper) || shadeDisabled.getBoolean(helper)
                || (boolean) popOver.invoke(mode.get(helper)) || detailOpening.getBoolean(null)
                || detailShowing.getBoolean(null) || detailClosing.getBoolean(null)
                || customizing.getBoolean(null)) return false;
        int state = current.getInt(helper);
        View root = (View) panelRoot.get(helper);
        return (state == QS || state == NOTIFICATIONS || state == TRANSITION)
                && root != null && root.isAttachedToWindow();
    }

    void setHeaderStart(Object nativeHandler, boolean inside) throws IllegalAccessException {
        changeSpot.setBoolean(nativeHandler, inside);
    }

    float slop(Object nativeHandler) throws IllegalAccessException {
        return touchSlop.getInt(nativeHandler);
    }

    boolean isDown(Object nativeHandler) throws IllegalAccessException {
        return direction.get(nativeHandler) == down;
    }

    void blockHorizontal(Object nativeHandler) throws IllegalAccessException {
        direction.set(nativeHandler, undecided);
        changeSpot.setBoolean(nativeHandler, false);
    }

    InitialYGate originGate(Object nativeHandler) throws ReflectiveOperationException {
        if (intercepted.getBoolean(nativeHandler)) return null;
        View statusHeader = (View) header.get(lazyValue.invoke(headerLazy.get(nativeHandler)));
        if (initialY.getFloat(nativeHandler) < statusHeader.getMeasuredHeight()) return null;
        return new InitialYGate(initialY, nativeHandler);
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
        if (current.getInt(helper) == TRANSITION) {
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
