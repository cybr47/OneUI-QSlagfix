package com.samsung.music.qs.lagfix;

import android.content.SharedPreferences;

/**
 * Shared constants and Remote Preferences commit verification logic for ShadeSwitch's settings UI
 * and SystemUI hook entry point.
 */
public final class ShadeSwitchPrefs {
    /** Remote preferences group name shared between the settings app and SystemUI hook entry point. */
    public static final String PREFS_GROUP = "shade_switch";

    /**
     * Preference key controlling panel-switching gesture mode:
     * <ul>
     *   <li>{@code false} (default): Samsung's original, unmodified horizontal panel gesture and transition.
     *   <li>{@code true}: the area-increase header-only vertical pull (notification/QS header and date row),
     *       with horizontal panel switching disabled.
     * </ul>
     */
    public static final String KEY_VERTICAL_SWIPE = "vertical_swipe_to_switch_panels";

    /** Hides the compact tile row on Samsung's Together notification panel. */
    public static final String KEY_HIDE_QUICK_QS_ROW = "hide_quick_qs_row";

    /** Off ({@code false}) by default: selects stock Samsung horizontal swipe and animation. */
    public static final boolean DEFAULT_VERTICAL_SWIPE = false;

    /** Switch label in ShadeSwitch settings. */
    public static final String SWITCH_TITLE = "Vertical swipe to switch panels";

    /** Switch description in ShadeSwitch settings. */
    public static final String SWITCH_DESCRIPTION =
            "In Separate style, swipe down from the panel header or date row to switch between Notifications and Quick Settings. Notifications and media controls keep their normal gestures.";

    /** Notice shown only after a confirmed remote write succeeds. */
    public static final String RESTART_SYSTEMUI_NOTICE = "Restart SystemUI to apply.";

    /** Notice shown while waiting for LSPosed's XposedService binder. */
    public static final String SERVICE_WAITING_NOTICE =
            "Waiting for LSPosed service\u2026 Enable ShadeSwitch in LSPosed to edit settings.";

    /** Notice shown if LSPosed's XposedService binder disconnects. */
    public static final String SERVICE_DISCONNECTED_NOTICE =
            "LSPosed service disconnected. Reopen from LSPosed to edit settings.";

    /** Prefix for remote preference read failures. */
    public static final String LOAD_FAILED_NOTICE =
            "Failed to load Remote Preferences from LSPosed.";

    /** Prefix for remote preference write failures. */
    public static final String SAVE_FAILED_NOTICE =
            "Failed to save setting to LSPosed Remote Preferences.";

    private ShadeSwitchPrefs() {
    }

    /**
     * Result of writing a switch to Remote Preferences.
     */
    public static final class SaveResult {
        public final boolean success;
        public final boolean currentValue;
        public final String message;

        SaveResult(boolean success, boolean currentValue, String message) {
            this.success = success;
            this.currentValue = currentValue;
            this.message = message;
        }
    }

    /**
     * Reads the current {@link #KEY_VERTICAL_SWIPE} value from {@code remotePrefs}.
     */
    public static boolean readVerticalSwipe(SharedPreferences remotePrefs) {
        if (remotePrefs == null) {
            throw new IllegalStateException("RemotePreferences instance is null");
        }
        return remotePrefs.getBoolean(KEY_VERTICAL_SWIPE, DEFAULT_VERTICAL_SWIPE);
    }

    public static SaveResult commitVerticalSwipe(
            SharedPreferences remotePrefs, boolean desiredValue, boolean previousValue) {
        return commitBoolean(remotePrefs, KEY_VERTICAL_SWIPE, desiredValue, previousValue);
    }

    /**
     * Synchronously commits {@code desiredValue} to {@code remotePrefs} via
     * {@link SharedPreferences.Editor#commit()}, verifies the committed value, and restores
     * {@code previousValue} in memory if the remote commit failed.
     *
     * @param remotePrefs   connected libxposed RemotePreferences instance (may be null if disconnected)
     * @param key           remote preference to update
     * @param desiredValue  new switch state requested by the user
     * @param previousValue switch state prior to the toggle, restored if the remote write fails
     */
    static SaveResult commitBoolean(
            SharedPreferences remotePrefs, String key, boolean desiredValue, boolean previousValue) {
        if (remotePrefs == null) {
            return new SaveResult(false, previousValue, SERVICE_DISCONNECTED_NOTICE);
        }
        try {
            SharedPreferences.Editor editor = remotePrefs.edit();
            if (editor == null) {
                return new SaveResult(false, previousValue, SAVE_FAILED_NOTICE);
            }
            boolean committed = editor.putBoolean(key, desiredValue).commit();
            if (!committed) {
                // In libxposed RemotePreferences.Editor.commit(), doUpdate() mutates the local map
                // before doCommit() calls IXposedService.updateRemotePreferences(). Roll back the
                // in-memory map to previousValue when doCommit() returns false.
                try {
                    SharedPreferences.Editor rollback = remotePrefs.edit();
                    if (rollback != null) {
                        rollback.putBoolean(key, previousValue).commit();
                    }
                } catch (Throwable ignored) {
                }
                return new SaveResult(false, previousValue, SAVE_FAILED_NOTICE);
            }
            boolean readBack = remotePrefs.getBoolean(key, !desiredValue);
            if (readBack != desiredValue) {
                return new SaveResult(false, readBack, SAVE_FAILED_NOTICE);
            }
            return new SaveResult(true, desiredValue, RESTART_SYSTEMUI_NOTICE);
        } catch (Throwable error) {
            String detail = error.getMessage();
            String message = (detail != null && !detail.isEmpty())
                    ? SAVE_FAILED_NOTICE + " (" + detail + ")"
                    : SAVE_FAILED_NOTICE;
            return new SaveResult(false, previousValue, message);
        }
    }
}
