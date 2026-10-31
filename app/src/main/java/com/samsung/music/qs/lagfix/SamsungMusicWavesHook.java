package com.samsung.music.qs.lagfix;

import android.graphics.PorterDuff;
import android.util.Log;
import android.view.View;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import io.github.libxposed.api.XposedModule;

/** One UI 9 media wave sampling, installed once in the SystemUI main process. */
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
        ClassLoader loader = param.getClassLoader();
        installLegacyWave(loader);
        installColorfulWave(loader);
        NoSwipePanelHooks.install(this, loader);
    }

    private void installLegacyWave(ClassLoader loader) {
        try {
            Class<?> renderer = Class.forName(
                    "com.android.systemui.media.audiovisseekbar.renderer.track.auto.MultiWaveAreaTrackRenderer",
                    false, loader);
            Class<?> config = Class.forName(
                    "com.android.systemui.media.audiovisseekbar.config.AudioVisSeekBarConfig", false, loader);
            Field step = intField(renderer, "stepX");
            Constructor<?> constructor = renderer.getDeclaredConstructor(View.class, config);
            hook(constructor).setId("legacy-wave-sampling").intercept(chain -> {
                Object result = chain.proceed();
                // The original optimization. No draw/frame callbacks are hooked.
                raiseStep(step, chain.getThisObject(), 10);
                return result;
            });
            debug("Legacy wave sampling installed (minimum stepX=10)", null);
        } catch (Throwable error) {
            debug("Legacy renderer unavailable; leaving that renderer unchanged", error);
        }
    }

    private void installColorfulWave(ClassLoader loader) {
        try {
            Class<?> style = Class.forName(
                    "com.android.systemui.media.colorfulwave.renderer.MultiWaveAreaTrackRenderer$WaveStyle",
                    false, loader);
            Class<?> renderer = Class.forName(
                    "com.android.systemui.media.colorfulwave.renderer.MultiWaveAreaTrackRenderer", false, loader);
            Field step = intField(style, "stepX");
            Field styles = renderer.getDeclaredField("waveStyles");
            styles.setAccessible(true);
            Constructor<?> constructor = style.getDeclaredConstructor(
                    int.class, int.class, int.class, PorterDuff.Mode.class,
                    float.class, float.class, float.class, float.class, float.class,
                    float.class, float.class, int.class);
            Method setStyles = renderer.getDeclaredMethod("setWaveStyles", List.class);
            // Resolve the complete contract before registering either hook.
            hook(constructor).setId("colorful-wave-sampling").intercept(chain -> {
                Object result = chain.proceed();
                // draw() clamps this renderer's stepX to 1..8; 10 would still become 8.
                raiseStep(step, chain.getThisObject(), 8);
                return result;
            });
            hook(setStyles).setId("colorful-wave-style-updates").intercept(chain -> {
                Object result = chain.proceed();
                // Samsung copies mutable styles into existing instances on album/theme changes.
                Object value = styles.get(chain.getThisObject());
                if (value instanceof List<?> list) {
                    for (Object wave : list) {
                        if (style.isInstance(wave)) raiseStep(step, wave, 8);
                    }
                }
                return result;
            });
            debug("Colorful wave sampling installed (effective stepX=8)", null);
        } catch (Throwable error) {
            debug("Colorful renderer unavailable; leaving that renderer unchanged", error);
        }
    }

    private static Field intField(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        if (field.getType() != int.class) throw new NoSuchFieldException(name + " must be int");
        field.setAccessible(true);
        return field;
    }

    private static void raiseStep(Field field, Object receiver, int minimum) throws IllegalAccessException {
        if (field.getInt(receiver) < minimum) field.setInt(receiver, minimum);
    }

    void debug(String message, Throwable error) {
        if (BuildConfig.DEBUG) log(Log.INFO, "OneUI-QSlagfix", message, error);
    }
}
