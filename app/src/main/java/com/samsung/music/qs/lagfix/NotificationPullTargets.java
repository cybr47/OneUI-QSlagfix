package com.samsung.music.qs.lagfix;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Finds notification-row touch targets from the shared shade window, not just its
 * NotificationPanelView child. Only non-row downward gestures may be transferred.
 */
final class NotificationPullTargets {
    private final Class<?> panelType, stackType, notificationRow;
    private final Field panelController, controllerHelper, shadeRoot;
    private final Field windowEventHandler, outerController, stackController, stackSplitHelper;
    private final Method childAtRawPosition, isMediaNotification;
    private WeakReference<View> cachedPanel, cachedStack;

    NotificationPullTargets(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> windowType = loader.loadClass("com.android.systemui.shade.NotificationShadeWindowView");
        windowEventHandler = NativePanels.field(windowType, "mInteractionEventHandler");
        Class<?> eventHandlerType = loader.loadClass(
                "com.android.systemui.shade.NotificationShadeWindowViewController$1");
        outerController = NativePanels.field(eventHandlerType, "this$0");
        Class<?> controllerType = loader.loadClass(
                "com.android.systemui.shade.NotificationShadeWindowViewController");
        stackController = NativePanels.field(controllerType, "mNotificationStackScrollLayoutController");
        Class<?> stackControllerType = loader.loadClass(
                "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController");
        stackSplitHelper = NativePanels.field(stackControllerType, "mPanelSplitHelper");
        panelType = loader.loadClass("com.android.systemui.shade.NotificationPanelView");
        panelController = NativePanels.field(panelType, "secNPVcontroller");
        controllerHelper = NativePanels.field(panelController.getType(), "panelSplitHelper");
        Class<?> helperType = loader.loadClass("com.android.systemui.shade.SecPanelSplitHelper");
        shadeRoot = NativePanels.field(helperType, "shadeRootView");
        stackType = loader.loadClass("com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout");
        childAtRawPosition = NativePanels.method(stackType, "getChildAtRawPosition", float.class, float.class);
        notificationRow = loader.loadClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow");
        Class<?> activatable = loader.loadClass("com.android.systemui.statusbar.notification.row.ActivatableNotificationView");
        isMediaNotification = NativePanels.method(activatable, "isMediaNotification", notificationRow);
    }

    Object helperFromWindow(View window) throws IllegalAccessException {
        // Verified One UI 9 window -> controller -> stack-controller -> splitter path.
        // It remains available when a separate-mode Compose layout has no NPV child.
        Object eventHandler = windowEventHandler.get(window);
        if (eventHandler != null) {
            Object controller = outerController.get(eventHandler);
            Object stack = controller == null ? null : stackController.get(controller);
            Object split = stack == null ? null : stackSplitHelper.get(stack);
            if (split != null) return split;
        }
        View panel = cachedPanel == null ? null : cachedPanel.get();
        if (panel == null || !panel.isAttachedToWindow() || panel.getRootView() != window.getRootView()) {
            panel = find(window, panelType);
            cachedPanel = panel == null ? null : new WeakReference<>(panel);
        }
        if (panel == null) return null;
        Object controller = panelController.get(panel);
        return controller == null ? null : controllerHelper.get(controller);
    }

    SwipeSession.Start classify(NativePanels panels, Object helper, MotionEvent down)
            throws ReflectiveOperationException {
        if (!panels.containsDown(helper, down)) return SwipeSession.Start.NONE;
        // Samsung's shadeRootView need not be the NSSL itself. A strict cast here made
        // body gestures silently fall back to header-only behavior on some layouts.
        View candidate = (View) shadeRoot.get(helper);
        View stack = stackType.isInstance(candidate) ? candidate : null;
        if (stack == null) {
            stack = cachedStack == null ? null : cachedStack.get();
            View root = panels.panelRoot(helper);
            if (stack == null || !stack.isAttachedToWindow()
                    || root == null || stack.getRootView() != root.getRootView()) {
                stack = root == null ? null : find(root, stackType);
                cachedStack = stack == null ? null : new WeakReference<>(stack);
            }
        }
        if (stack == null || !stack.isAttachedToWindow()) {
            // Fail closed: never steal a notification-row gesture without a row hit test.
            return panels.inHeader(helper, down) ? SwipeSession.Start.HEADER : SwipeSession.Start.NONE;
        }
        if (!stack.isShown()) return SwipeSession.Start.NOTIFICATIONS;
        Object child = childAtRawPosition.invoke(stack, down.getRawX(), down.getRawY());
        // Native notification expansion/scrolling is preserved. Samsung's promoted
        // media row and empty/decor regions can initiate a panel-switch pull.
        if (notificationRow.isInstance(child) && !(boolean) isMediaNotification.invoke(null, child)) {
            return SwipeSession.Start.NONE;
        }
        return panels.inHeader(helper, down)
                ? SwipeSession.Start.HEADER : SwipeSession.Start.NOTIFICATIONS;
    }

    /**
     * Cancel only the notification content's prior child stream. Dispatching a
     * synthetic CANCEL through NotificationShadeWindowView itself would also
     * invoke the outer shade/window interaction controller, disrupting the
     * clock/date and scrim while the native panel switch is being handed off.
     *
     * The source event is window-local; translate its coordinates to the
     * notification content's local space before dispatching to its children.
     */
    boolean cancelContentTouch(View window, MotionEvent source) {
        View panel = cachedPanel == null ? null : cachedPanel.get();
        if (panel == null || !panel.isAttachedToWindow()
                || panel.getRootView() != window.getRootView()) {
            panel = find(window, panelType);
            cachedPanel = panel == null ? null : new WeakReference<>(panel);
        }
        // On a layout where the NPV isn't mounted, the stack is still the
        // media/notification touch host. Never send CANCEL to the outer window.
        if (panel == null) panel = find(window, stackType);
        if (panel == null || panel == window || !panel.isAttachedToWindow()) {
            return false;
        }

        MotionEvent cancel = MotionEvent.obtainNoHistory(source);
        int[] windowLocation = new int[2];
        int[] contentLocation = new int[2];
        window.getLocationOnScreen(windowLocation);
        panel.getLocationOnScreen(contentLocation);
        cancel.offsetLocation(windowLocation[0] - contentLocation[0],
                windowLocation[1] - contentLocation[1]);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        try {
            panel.dispatchTouchEvent(cancel);
            return true;
        } finally {
            cancel.recycle();
        }
    }

    private static View find(View root, Class<?> type) {
        if (root == null) return null;
        if (type.isInstance(root)) return root;
        if (!(root instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = find(group.getChildAt(i), type);
            if (found != null) return found;
        }
        return null;
    }
}
