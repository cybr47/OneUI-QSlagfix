# Samsung Music QS Lag Fix

A minimal Xposed module to fix frame drops in the Quick Settings panel on Samsung devices when music is playing.

## The Issue
Samsung's `AudioVisSeekBar` uses a "squiggly" wave animation. By default, it renders with `stepX = 2`, which is an excessive high resolution that forces frequent and expensive path calculations. On midrange devices, this can result in up to 30% CPU usage increase by SystemUI, causing the panel to skip frames when expanding or interacting with the QS panel.

## The Fix
This module hooks the constructor of `MultiWaveAreaTrackRenderer` and forces `stepX` to `10`. This reduces the path complexity significantly with almost no visible impact on the animation quality, eliminating the Quick Settings stutter during music playback.

While `10` is a balanced sweet spot, values like `15` may provide slightly better performance on very low-end devices. However, anything past `20` results in visual diminishing returns and distorted waves with no measurable improvements to CPU usage.

## Target
- **Package**: `com.android.systemui`
- **Class**: `com.android.systemui.media.audiovisseekbar.renderer.track.auto.MultiWaveAreaTrackRenderer`
- **Parameter Modified**: `stepX` (Int)

## Installation
1. Install APK.
2. Enable in LSPosed/Xposed.
3. Select **System UI** scope.
4. Restart System UI or Reboot.

## License
Apache License 2.0.

**Attribution Requirement:**
As per the [NOTICE](NOTICE) file, any project that incorporates this specific `stepX` optimization must provide clear acknowledgement of this project as the original source of the tweak.
