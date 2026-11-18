package com.samsung.music.qs.lagfix;

import android.view.MotionEvent;
import android.view.View;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** The same clipped/group-aware hit test used by Samsung's notification expansion code. */
final class NotificationPullTargets {
    final Method dispatchPanel, interceptPanel;
    private final Field panelController, controllerHelper, shadeRoot;
    private final Class<?> notificationRow, stackType;
    private final Method childAtRawPosition, isMediaNotification, touchOccupied;

    NotificationPullTargets(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> panel = loader.loadClass("com.android.systemui.shade.NotificationPanelView");
        dispatchPanel = NativePanels.method(panel, "dispatchTouchEvent", MotionEvent.class);
        interceptPanel = NativePanels.method(panel, "onInterceptTouchEvent", MotionEvent.class);
        panelController = NativePanels.field(panel, "secNPVcontroller");
        touchOccupied = NativePanels.method(panelController.getType(), "isPanelTouchOccupied");
        controllerHelper = NativePanels.field(panelController.getType(), "panelSplitHelper");
        // Samsung stores R.id.notification_stack_scroller on the split helper when the panel binds.
        shadeRoot = NativePanels.field(controllerHelper.getType(), "shadeRootView");
        stackType = loader.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
        childAtRawPosition = NativePanels.method(stackType, "getChildAtRawPosition", float.class, float.class);
        notificationRow = loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow");
        Class<?> activatable = loader.loadClass("com.android.systemui.statusbar.notification.row.ActivatableNotificationView");
        isMediaNotification = NativePanels.method(activatable, "isMediaNotification", notificationRow);
    }

    Object helper(View panel) throws IllegalAccessException {
        Object controller = panelController.get(panel);
        return controller == null ? null : controllerHelper.get(controller);
    }

    boolean touchOccupied(View panel) throws ReflectiveOperationException {
        Object controller = panelController.get(panel);
        return controller == null || (boolean) touchOccupied.invoke(controller);
    }

    SwipeSession.Start classify(NativePanels panels, Object helper, MotionEvent down)
            throws ReflectiveOperationException {
        if (!panels.containsDown(helper, down)) return SwipeSession.Start.NONE;
        View stack = (View) shadeRoot.get(helper);
        if (!stackType.isInstance(stack) || !stack.isAttachedToWindow()) {
            return panels.inHeader(helper, down) ? SwipeSession.Start.HEADER : SwipeSession.Start.NONE;
        }
        // A hidden stack has no touchable rows, even if it still has recycled children.
        if (!stack.isShown()) return SwipeSession.Start.NOTIFICATIONS;
        Object child = childAtRawPosition.invoke(stack, down.getRawX(), down.getRawY());
        // Samsung's promoted player is itself an ExpandableNotificationRow. The dedicated
        // MediaContainerView and empty/decor areas also allow a pull. All other rows retain it.
        if (notificationRow.isInstance(child) && !(boolean) isMediaNotification.invoke(null, child)) {
            return SwipeSession.Start.NONE;
        }
        return SwipeSession.Start.NOTIFICATIONS;
    }
}
