<script lang="ts">
  interface Props {
    /** Newest sample last. */
    samples: number[];
    color: string;
    height?: number;
  }

  let { samples, color, height = 44 }: Props = $props();

  const WIDTH = 260;

  // Scale against the window's own peak so a quiet tunnel still shows shape,
  // with a floor so noise near zero does not fill the graph.
  let path = $derived.by(() => {
    if (samples.length < 2) return { line: "", area: "" };
    const peak = Math.max(...samples, 1024);
    const step = WIDTH / (samples.length - 1);
    const points = samples.map((value, i) => {
      const x = i * step;
      const y = height - (value / peak) * (height - 4) - 2;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    });
    return {
      line: `M${points.join("L")}`,
      area: `M0,${height} L${points.join("L")} L${WIDTH},${height} Z`,
    };
  });

  const gradientId = `spark-${Math.random().toString(36).slice(2, 9)}`;
</script>

<svg
  class="spark"
  viewBox="0 0 {WIDTH} {height}"
  preserveAspectRatio="none"
  aria-hidden="true"
>
  <defs>
    <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color={color} stop-opacity="0.32" />
      <stop offset="100%" stop-color={color} stop-opacity="0" />
    </linearGradient>
  </defs>
  {#if path.area}
    <path d={path.area} fill="url(#{gradientId})" />
    <path d={path.line} fill="none" stroke={color} stroke-width="1.75"
      stroke-linejoin="round" stroke-linecap="round" />
  {/if}
</svg>

<style>
  .spark {
    display: block;
    width: 100%;
    height: 44px;
  }
</style>
