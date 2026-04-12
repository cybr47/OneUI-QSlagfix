# Samsung QS Lag Fix (UN1CA)

A minimal Xposed module to fix frame drops in the Quick Settings panel on Samsung devices running the UN1CA 3 ROM when music is playing.

## The Issue
Samsung's `AudioVisSeekBar` (QS Media Player's progress bar) uses a wave animation. By default, on flagship OneUI port ROMs (like UN1CA) it renders with `stepX = 2`, which is an excessive high resolution that forces frequent and expensive path calculations. On midrange devices, this can result in up to 30% CPU usage increase by SystemUI, causing the panel to skip frames when expanding or interacting with the QS panel.

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

## Links
- [PayPal](https://www.paypal.com/donate/?hosted_button_id=BJAJW4755BXFY)
- Revolut Tag @danielem47
- Litecoin/LTC (ltc1qlens88rlj8vpjt9r4kt2mqrma6nq3v3aylgef6)
- Etherium/ETH (0x2c8d02EA7202eaf9DAC14af6ABc178Cb34Cd3f00)

## License
Apache License 2.0.

**Attribution Requirement:**
As per the [NOTICE](NOTICE) file, any project that incorporates this specific `stepX` optimization must provide clear acknowledgement of this project as the original source of the tweak.
