# ShadeSwitch v2

A streamlined LSPosed module for **Samsung One UI 9 / Android 17**. This branch starts from the `area-increase` implementation and reuses the settings activity and Together-mode Quick QS row option from `tgtr`.

## Features

- **Vertical swipe to switch panels — OFF (default):** Leave Samsung's original **Separate mode** horizontal swipe recognizer, animator and release/fling behavior **untouched**. No GlassUI replacement, custom fade, manually increased frame count or custom touch interception. Native performance is not yet optimized or verified.
- **Vertical swipe to switch panels — ON:** Use the **original `area-increase`** header/date-row downward pull to change between Notifications and Quick Settings in Separate mode, disabling horizontal panel switching while this option is selected. **Notification body and media card are not gesture start areas.** Samsung retains the actual drag/release animation.
- **Together style → Hide Quick QS row:** Optional independent switch, ported from `tgtr`. Hides compact tiles without changing Samsung's full Quick Settings screen. It is effective in Together style and does not change Separate mode.

The settings UI uses LSPosed Remote Preferences; restart SystemUI after toggling options. Both switches are off by default on a new install. Existing settings with the same keys are retained when upgrading.

## Source separation

- Original `area-increase` files left unchanged: `NativePanels.java`, `NoSwipePanelHooks.java`, `SwipeSession.java`, and `InitialYGate.java`.
- Ported from `tgtr`: `ShadeSwitchSettingsActivity.java`, `ShadeSwitchPrefs.java`, `TogetherQuickQsHooks.java`, and settings resources/configuration.
- `SamsungMusicWavesHook.java` installs the **header-only** hooks **only when enabled** and the Together compact-row hook **only when enabled**. When vertical gestures are disabled, **no horizontal animation hooks are installed**.
- **No legacy QS lagfix hooks or media-wave `stepX` adjustments are installed or bundled.** The original experimental sampling code remains only in older Git branches. The original Android package ID (`com.samsung.music.qs.lagfix`) and Xposed entry class name are retained for update compatibility; those strings do not install additional hooks.

## Horizontal animation performance — baseline first

The user reported frame jitter in Samsung's original horizontal panel switch, especially while the media card is shown. **v2 does not claim to fix that issue yet.** Frame rate is limited by display refresh and work per frame; fabricating additional interpolation frames cannot remove CPU/GPU stalls. An optimization should follow a device trace (ideally Perfetto with SurfaceFlinger FrameTimeline, SystemUI render thread and CPU scheduling), comparing transitions with and without media playback/card visible. Fix the observed bottleneck without replacing Samsung's animation.

## Compatibility

Built using Android API 37, Java 17, libxposed API 102 with the LSPosed service dependency. The module targets `com.android.systemui` on the validated One UI 9 build documented in `docs/ONEUI9-ANALYSIS.md`. This project modifies proprietary UI behavior; test on-device before relying on it.
