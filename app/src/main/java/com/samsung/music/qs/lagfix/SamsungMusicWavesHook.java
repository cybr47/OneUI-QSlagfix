package com.samsung.music.qs.lagfix;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class SamsungMusicWavesHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.android.systemui")) return;

        final String rendererClass = "com.android.systemui.media.audiovisseekbar.renderer.track.auto.MultiWaveAreaTrackRenderer";

        XposedHelpers.findAndHookConstructor(rendererClass, lpparam.classLoader,
            "android.view.View",
            "com.android.systemui.media.audiovisseekbar.config.AudioVisSeekBarConfig",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    // Reduce wave path complexity (Default: 2) to fix QS stutter
                    XposedHelpers.setIntField(param.thisObject, "stepX", 10);
                }
        });

        XposedBridge.log("Samsung Music QS Lag Fix: Hooked (stepX=10)");
    }
}
