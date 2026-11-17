package com.samsung.music.qs.lagfix;

import android.util.Log;
import io.github.libxposed.api.XposedModule;

/**
 * Module entry point for the {@code cust} branch of OneUI-QSlagfix.
 *
 * <p>On One UI 9 (Android 17), the stock media waveform renderers
 * ({@code audiovisseekbar.renderer.track.auto.MultiWaveAreaTrackRenderer} and
 * {@code colorfulwave.renderer.MultiWaveAreaTrackRenderer}) already early-out when idle and cache
 * their gradients and xfermodes; raising {@code stepX} from {@code 2} to {@code 8}/{@code 10} only
 * coarsens the sine path into straight segments without improving panel transition performance.
 * Therefore, {@code cust} omits the media-wave coarsening hooks while preserving this class as the
 * {@code META-INF/xposed/java_init.list} module entry point and installing the GlassUI-style
 * horizontal Notifications <-> Quick Settings transition hooks.
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
        if (BuildConfig.DEBUG) debug("SystemUI package ready: installing hooks", null);
        // Visible in release builds: if this line is missing from the LSPosed log, the module never loaded.
        note("Loaded in SystemUI, module version " + BuildConfig.VERSION_NAME + " (" + BuildConfig.BUILD_TYPE + ")");
        ClassLoader loader = param.getClassLoader();
        HorizontalPanelTransitionHooks.install(this, loader);
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
