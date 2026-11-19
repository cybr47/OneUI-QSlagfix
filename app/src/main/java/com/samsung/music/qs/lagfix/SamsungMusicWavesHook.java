package com.samsung.music.qs.lagfix;

import android.content.SharedPreferences;
import android.util.Log;
import io.github.libxposed.api.XposedModule;

/**
 * ShadeSwitch v2 Glass Fade Perf: no visual/gesture/state changes from v2-glass-fade.
 *
 * OFF (default): Direct native horizontal page movement with GlassUI's
 * crossfade, without elastic springs, bounce or independent settling inertia.
 * Samsung retains gesture recognition and panel state.
 *
 * ON: Install the original area-increase NoSwipePanelHooks (header-only
 * vertical pulls with horizontal panel switching disabled).
 *
 * Together-mode quick-QS row hiding is an independent preference.
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
        if (!targetProcess || installed
                || !"com.android.systemui".equals(param.getPackageName())) {
            return;
        }
        installed = true;
        boolean vertical = readPreference(ShadeSwitchPrefs.KEY_VERTICAL_SWIPE);
        boolean hideQuickQs = readPreference(ShadeSwitchPrefs.KEY_HIDE_QUICK_QS_ROW);

        note("Loaded in SystemUI, module version " + BuildConfig.VERSION_NAME
                + " (" + BuildConfig.BUILD_TYPE + ", gestures="
                + (vertical ? "header-only-vertical" : "glass-fade-direct")
                + ", hideQuickQsRow=" + hideQuickQs + ")");

        ClassLoader loader = param.getClassLoader();
        if (vertical) {
            // EXACT original implementation from area-increase, not tgtr's
            // notification-body interception or custom header animation.
            NoSwipePanelHooks.install(this, loader);
        } else {
            // Keep the tested GlassUI crossfade on native Samsung panels,
            // using direct native progress with no extra spring/frame loop.
            HorizontalPanelTransitionHooks.install(this, loader);
        }

        if (hideQuickQs) {
            TogetherQuickQsHooks.install(this, loader);
        }

        // The legacy area-increase wave-sampling hooks are deliberately omitted
        // for a clean Samsung rendering baseline. Coarsening the waveform's
        // spatial sampling does not increase display frame rate.
    }

    private boolean readPreference(String key) {
        try {
            SharedPreferences prefs = getRemotePreferences(ShadeSwitchPrefs.PREFS_GROUP);
            if (prefs != null) return prefs.getBoolean(key, false);
        } catch (Throwable error) {
            warn("Unable to read remote preference " + key, error);
        }
        return false;
    }

    void debug(String message, Throwable error) {
        if (BuildConfig.DEBUG) log(Log.INFO, "ShadeSwitch", message, error);
    }

    void note(String message) {
        log(Log.INFO, "ShadeSwitch", message, null);
    }

    void warn(String message, Throwable error) {
        log(Log.WARN, "ShadeSwitch", message, error);
    }
}
