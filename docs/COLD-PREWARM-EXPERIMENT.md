# Cold GPU pipeline warm-up experiment

## User-observed issue

The Samsung S25 on One UI 9 feels smooth during repeated native notification/QS panel transitions, but **the first shade pull and first horizontal transition after SystemUI restart, boot/unlock, or prolonged (~10 min) inactivity are visibly slower**. The successful GlassUI-style fade, native QS/notif visuals, slider/media interactions, and the existing two state-callback optimizations must remain intact.

This branch starts from `v2-glass-fade-eventperf` (2.6.4), **not** the older spring animation or the 2.6.2 hot-path experiment the user preferred less. The original `v2-glass-fade-eventperf` and `v2-glass-fade-constraints` branches are untouched.

## Perfetto evidence

The user's 2026-11-19 22:02:52 `v2-glass-fade-constraints` recording contains 1,187 NotificationShade surface frames, 74 full + 7 partial jank (6.82%). The highest actual surface duration is 84.8 ms at +2.825 seconds.

For that 84.8 ms frame, the SystemUI main-thread traversal `draw-VRI[NotificationShade]` waited ~74.5 ms in `postAndWait`, while its RenderThread was spending **~72.8 ms** in `CreateGraphicsPipeline` during Vulkan frame flush:
- ~36.7 ms `CreateGraphicsPipeline-CacheLookup`
- ~36.1 ms `CreateGraphicsPipeline-CompileAfterCacheMiss`
- `FillRectOp` within the same ~76 ms rendering task

Other slow frames involved `Recomposer:recompose` (~14–18 ms), Compose graphics-layer drawing and view traversal. The trace doesn't establish that the first-after-10-minutes case is identical; it merely proves an actual on-demand graphics pipeline compilation stall exists.

## Experiment

**Use only an independent synthetic GPU surface.** Install `ColdGpuPipelineWarmup` in the SystemUI process after ShadeSwitch's existing horizontal hooks. It reflects `ActivityThread.currentApplication()` and listens for protected `SCREEN_ON`, `SCREEN_OFF` and `USER_PRESENT` broadcasts, using a process-local non-exported receiver.

The worker owns a dedicated background HandlerThread and, when eligible, records a small 96×96 synthetic opaque fill, translucent rounded rectangle and clipped fill into a standalone `RenderNode`. A `HardwareRenderer` uses an off-screen `ImageReader` surface to submit **one** graphics frame, then closes its acquired Image and destroys the renderer and reader. The Android HWUI renderer instances share the process RenderThread. This could populate the exact same Vulkan pipeline cache used later by Samsung's real panel **if** the shader/paint/state combinations match.

Schedules: once shortly after SystemUI is ready, after screen-on/unlock, and no more often than once per 7 minutes while the display is interactive. No work occurs when the screen is off or during/just after an active ShadeSwitch horizontal gesture. A minimum 65-second gap prevents immediate duplicate warm-ups. A bounded log reports first five warm-up durations and results. An exception disables the experiment until next SystemUI restart while leaving the normal Glass Fade hooks operational.

**No** real panel view, Compose UI tree, clock/date, blur state, media player, gesture, layer, refresh rate, notification or lockscreen properties are modified. This is not a screenshot; the scene is synthetic and no pixels are accessed/stored.

## Caveats

A synthetic `FillRect` cannot guarantee matching Samsung's exact Vulkan graphics-pipeline key, render-target format, blending state or cache lifecycle. Even if successful, it doesn't precompose Samsung's native Compose QS hierarchy. `HardwareRenderer` shares RenderThread; the background worker prevents blocking the UI thread directly, but GPU prewarm can temporarily compete with visible work. Keep this as a **draft experiment** until tested.

The renderer destroys its resources in `finally`; its one buffered frame is explicitly acquired/closed when available. The worker is not scheduled when screen-off; startup hook/register failure leaves other hooks unchanged.

The strong <5.08% historical GlassUI target remains a **goal**, not a promised result. The most relevant metric is user-perceived **first animation responsiveness** after restart/unlock and after at least 10 minutes of shade inactivity, not a single pooled jank percentage.

## Test protocol

1. Disable the separate GlassUI LSPosed module; install this branch's signed `app-release.apk`, leave Vertical swipe OFF; restart SystemUI.
2. Confirm LSPosed reports `Cold GPU pipeline prewarm registered` and `Cold GPU synthetic prewarm #1: ...ms`. If it logs `disabled after failure`, the normal fade remains active but the experiment was not applied.
3. Perform a first notification/QS pull, then the first horizontal left/right swipe; confirm the exact existing fade and tile/media/notification visuals.
4. Lock the phone and leave it idle >10 minutes; unlock and try the first pull/swipe. Repeat after leaving it unlocked idle >10 minutes.
5. Capture a Perfetto trace including at least the **unlock + first pull**, looking for `ShadeSwitch:offscreenGpuPrewarm`, `CreateGraphicsPipeline`, `vkCreateGraphicsPipelines` and NotificationShade frame jank/p95/worst.
6. Compare with the 2.6.4 parent build under matched idle/gesture conditions. If it feels worse or introduces unusual SystemUI resource usage, reinstall 2.6.4 and restart SystemUI. It has the same package name and signing key.

This experiment does **not** hook native Vulkan APIs, persist proprietary shader code, or alter security-sensitive window contents.
