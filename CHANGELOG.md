# Changelog

## v2-glass-fade-cold-prewarm — experimental first-gesture GPU pipeline cache preparation

- Keep all native panel visual effects, fade, gesture tracking and event/constraint coalescing from 2.6.4 unchanged.
- Use an isolated one-shot GPU draw on a tiny offscreen ImageReader/RenderNode surface to opportunistically compile shared HWUI fill/rounded-rectangle pipelines before first shade use.
- Schedule after SystemUI readiness, screen-on and unlock; maintain every seven minutes only while screen remains interactive, never during native horizontal slide.
- Dedicated background HandlerThread; prompt GPU buffer consumption, resource cleanup, bounded logs and one-failure disable.
- Add pure scheduling-policy tests to forbid screen-off and active-transition GPU work.
- Motivated by recorded 84.8ms shade frame, including 72.8ms Vulkan pipeline creation. Runtime effectiveness is unproven.

## v2-glass-fade-eventperf — 2.6.4 baseline

- Keep native constraints optimization and scope-limited QS animator fan-out coalescing.
- Historical 6.82% shade jank reference from 2.6.3 is not a measured result for the new prewarm.
