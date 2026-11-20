package com.samsung.music.qs.lagfix;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RenderNode;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Trace;

import java.lang.reflect.Method;

/**
 * EXPERIMENTAL offscreen GPU warm-up for the first visible shade draw.
 *
 * In the user's 6.82% trace, the worst 84.8ms NotificationShade frame blocked
 * for ~72.8ms inside RenderThread CreateGraphicsPipeline, after a Vulkan cache
 * miss. The normal UI animations cannot avoid that compilation once the GPU
 * meets a new pipeline.
 *
 * Render an independent tiny synthetic rounded panel on an ImageReader surface
 * BEFORE a user opens the shade. This can make common FillRect/RRect/clip/blend
 * pipelines available inside HWUI's shared RenderThread/driver caches.
 *
 * No actual Samsung View, QS Compose state, notification, lockscreen or window
 * property is ever read or mutated here. It does not force an invisible QS
 * ComposeView visible, invoke app lifecycle callbacks, or change refresh rates.
 *
 * This is a probabilistic precompile experiment, not proof that the *exact*
 * Vulkan pipeline responsible for the stall was warmed. On any error the
 * experiment disables itself without changing ShadeSwitch's existing hooks.
 */
final class ColdGpuPipelineWarmup {
    private static volatile boolean horizontalGesture;
    private static volatile long lastGestureUptime;
    private static ColdGpuPipelineWarmup installed;

    private final SamsungMusicWavesHook module;
    private final Application application;
    private final PowerManager power;
    private final Handler worker;
    private final HandlerThread thread;
    private final Runnable pendingWarm;
    private final BroadcastReceiver displayReceiver;
    private volatile boolean disabled;
    private long lastWarmUptime;
    private int warmCount;
    private int failureCount;

    private ColdGpuPipelineWarmup(
            SamsungMusicWavesHook module, Application application) {
        this.module = module;
        this.application = application;
        this.power = (PowerManager) application.getSystemService(Context.POWER_SERVICE);
        this.thread = new HandlerThread("ShadeSwitch-GPU-Prewarm",
                android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        this.worker = new Handler(thread.getLooper());
        this.pendingWarm = this::runWarmSafely;
        this.displayReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent != null ? intent.getAction() : null;
                if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                    worker.removeCallbacks(pendingWarm);
                } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    schedule(1_000L);
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    schedule(650L);
                }
            }
        };
    }

    static void install(SamsungMusicWavesHook module) {
        if (installed != null) return;
        ColdGpuPipelineWarmup warmup = null;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object app = currentApplication.invoke(null);
            if (!(app instanceof Application application)) {
                module.warn("GPU prewarm unavailable: SystemUI Application not ready", null);
                return;
            }
            warmup = new ColdGpuPipelineWarmup(module, application);
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            // Receiver is SystemUI-process-local and handles protected system
            // broadcasts only. No exported listener or extra app permissions.
            application.registerReceiver(warmup.displayReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
            installed = warmup;
            module.note("Cold GPU pipeline prewarm registered"
                    + " (synthetic offscreen scene, screen-on + 7min idle)");
            warmup.schedule(1_500L);
        } catch (Throwable error) {
            if (warmup != null) {
                warmup.disabled = true;
                warmup.thread.quitSafely();
            }
            module.warn("GPU prewarm registration unavailable; normal panels intact", error);
        }
    }

    /** Called only when a native horizontal session starts or ends. */
    static void markHorizontalGesture(boolean active) {
        horizontalGesture = active;
        lastGestureUptime = SystemClock.uptimeMillis();
    }

    private void schedule(long delayMs) {
        if (disabled) return;
        worker.removeCallbacks(pendingWarm);
        worker.postDelayed(pendingWarm, delayMs);
    }

    private void runWarmSafely() {
        if (disabled) return;
        long now = SystemClock.uptimeMillis();
        long delay = ColdGpuWarmupPolicy.nextDelay(
                now, power != null && power.isInteractive(),
                horizontalGesture, lastGestureUptime,
                lastWarmUptime, warmCount);
        if (delay < 0L) return;
        if (delay > 0L) {
            schedule(delay);
            return;
        }
        try {
            if (!HardwareRenderer.isDrawingEnabled()) {
                schedule(ColdGpuWarmupPolicy.INTERVAL_MS);
                return;
            }
            long start = SystemClock.elapsedRealtime();
            Trace.beginSection("ShadeSwitch:offscreenGpuPrewarm");
            int result;
            try {
                result = renderOneOffscreenFrame();
            } finally {
                Trace.endSection();
            }
            long elapsed = SystemClock.elapsedRealtime() - start;
            lastWarmUptime = SystemClock.uptimeMillis();
            warmCount++;
            if (warmCount <= 5) {
                module.note("Cold GPU synthetic prewarm #" + warmCount
                        + ": " + elapsed + "ms, syncResult=" + result
                        + " (does not modify native shade views)");
            }
        } catch (Throwable failure) {
            // Do not retry unsupported GPU formats or broken drivers in
            // SystemUI. No visible views were modified.
            disabled = true;
            failureCount++;
            module.warn("GPU synthetic prewarm disabled after failure; normal shade retained",
                    failure);
            return;
        }
        schedule(ColdGpuWarmupPolicy.INTERVAL_MS);
    }

    private static int renderOneOffscreenFrame() {
        ImageReader reader = null;
        HardwareRenderer renderer = null;
        try {
            // 96x96 is deliberately independent of the screen, notification
            // content, secure windows and any real SystemUI view tree.
            reader = ImageReader.newInstance(96, 96, PixelFormat.RGBA_8888, 2);
            RenderNode node = new RenderNode("ShadeSwitch-RRect-Pipeline-Warm");
            node.setPosition(0, 0, 96, 96);
            Canvas canvas = node.beginRecording();

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.rgb(24, 24, 28));
            canvas.drawRect(0f, 0f, 96f, 96f, paint);

            paint.setColor(Color.argb(215, 49, 50, 57));
            canvas.drawRoundRect(3f, 3f, 93f, 93f, 18f, 18f, paint);

            paint.setColor(Color.argb(155, 230, 235, 241));
            canvas.save();
            canvas.clipRect(8f, 8f, 88f, 88f);
            canvas.drawRoundRect(15f, 24f, 79f, 56f, 12f, 12f, paint);
            canvas.restore();
            node.endRecording();

            renderer = new HardwareRenderer();
            renderer.setName("ShadeSwitch-Offscreen-GPU-Prewarm");
            renderer.setOpaque(false);
            renderer.setSurface(reader.getSurface());
            renderer.setContentRoot(node);
            int status = renderer.createRenderRequest().syncAndDraw();

            // A HardwareRenderer shares the real SystemUI RenderThread.
            // Never leave pending ImageReader buffers that could stall it.
            try (Image image = reader.acquireLatestImage()) {
                // No pixels are inspected, retained, or written to storage.
            }
            return status;
        } finally {
            if (renderer != null) {
                try { renderer.destroy(); } catch (Throwable ignored) { }
            }
            if (reader != null) {
                try { reader.close(); } catch (Throwable ignored) { }
            }
        }
    }
}
