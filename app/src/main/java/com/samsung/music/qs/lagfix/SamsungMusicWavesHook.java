package com.samsung.music.qs.lagfix;

import android.content.SharedPreferences;
import android.util.Log;
import io.github.libxposed.api.XposedModule;

/**
 * Module entry point for the {@code cust} branch of ShadeSwitch ({@code cybr47/OneUI-QSlagfix}).
 *
 * <p>Reads the {@link ShadeSwitchPrefs#KEY_VERTICAL_SWIPE} Remote Preference at SystemUI startup
 * and installs <b>only</b> the selected mode's hooks:
 * <ul>
 *   <li><b>Off ({@code false}, default)</b>: installs {@link HorizontalPanelTransitionHooks} with
 *       the eased {@code 50%} ({@code 0.50f}) outgoing fade.
 *   <li><b>On ({@code true})</b>: installs {@link NoSwipePanelHooks} from {@code area-increase},
 *       restoring the vertical header drag (including the expanded clock/date/button-row hit area
 *       and lockscreen shade support) and disabling horizontal panel switching.
 * </ul>
 * Media-wave coarsening hooks remain omitted in both modes.
 */
public final class SamsungMusicWavesHook extends XposedModule {
    private boolean targetProcess;
    private boolean installed;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        targetProcess = "com.android.systemui".equals(param.getProcessName());
        if (BuildConfig.DEBUG) {
            debug("Module loaded: process=" + param.getProcessName()
                    + "; target=" + targetProcess + "; version=" + BuildConfig.VERSION_NAME
                    + "; framework=" + getFrameworkName() + " " + getFrameworkVersion()
                    + "; api=" + getApiVersion(), null);
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        if (BuildConfig.DEBUG && "com.android.systemui".equals(param.getPackageName())) {
            debug("SystemUI package loaded: target=" + targetProcess, null);
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!targetProcess || installed || !"com.android.systemui".equals(param.getPackageName())) {
            return;
        }
        installed = true;
        boolean verticalSwipe = readVerticalSwipePreference();
        if (BuildConfig.DEBUG) {
            debug("SystemUI package ready: installing hooks (verticalSwipe=" + verticalSwipe + ")", null);
        }
        // Visible in release builds: if this line is missing from the LSPosed log, the module never loaded.
        note("Loaded in SystemUI, module version " + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.BUILD_TYPE + ", mode="
                + (verticalSwipe ? "vertical" : "horizontal") + ")");
        ClassLoader loader = param.getClassLoader();
        if (verticalSwipe) {
            NoSwipePanelHooks.install(this, loader);
        } else {
            HorizontalPanelTransitionHooks.install(this, loader);
        }
    }

    private boolean readVerticalSwipePreference() {
        try {
            SharedPreferences prefs = getRemotePreferences(ShadeSwitchPrefs.PREFS_GROUP);
            if (prefs != null) {
                return prefs.getBoolean(
                        ShadeSwitchPrefs.KEY_VERTICAL_SWIPE,
                        ShadeSwitchPrefs.DEFAULT_VERTICAL_SWIPE);
            }
        } catch (Throwable error) {
            warn("Unable to read remote preferences; defaulting to horizontal swipe mode", error);
        }
        return ShadeSwitchPrefs.DEFAULT_VERTICAL_SWIPE;
    }

    /** Verbose diagnostics: debug builds only. */
    void debug(String message, Throwable error) {
        if (BuildConfig.DEBUG) log(Log.INFO, "OneUI-QSlagfix", message, error);
    }

    /**
     * Status lines that matter in every build, release included. They are written once per SystemUI
     * start (or once per failure), never per touch event or frame.
     */
    void note(String message) {
        log(Log.INFO, "OneUI-QSlagfix", message, null);
    }

    void warn(String message, Throwable error) {
        log(Log.WARN, "OneUI-QSlagfix", message, error);
    }
}
