<script lang="ts">
  interface Props {
    /** Newest sample last. */
    samples: number[];
    color: string;
    height?: number;
  }

  let { samples, color, height = 52 }: Props = $props();

  const WIDTH = 260;

  // Catmull-Rom -> cubic Bezier conversion, so the line reads as a smooth
  // curve instead of the jagged polyline a straight point-to-point path
  // gives you. Purely cosmetic — the underlying samples are unchanged.
  function smoothPath(points: { x: number; y: number }[]): string {
    if (points.length < 2) return "";
    if (points.length === 2) {
      return `M${points[0].x},${points[0].y}L${points[1].x},${points[1].y}`;
    }
    let d = `M${points[0].x},${points[0].y}`;
    for (let i = 0; i < points.length - 1; i++) {
      const p0 = points[i - 1] ?? points[i];
      const p1 = points[i];
      const p2 = points[i + 1];
      const p3 = points[i + 2] ?? p2;
      const c1x = p1.x + (p2.x - p0.x) / 6;
      const c1y = p1.y + (p2.y - p0.y) / 6;
      const c2x = p2.x - (p3.x - p1.x) / 6;
      const c2y = p2.y - (p3.y - p1.y) / 6;
      d += `C${c1x.toFixed(1)},${c1y.toFixed(1)} ${c2x.toFixed(1)},${c2y.toFixed(1)} ${p2.x.toFixed(1)},${p2.y.toFixed(1)}`;
    }
    return d;
  }

  // Scale against the window's own peak so a quiet tunnel still shows shape,
  // with a floor so noise near zero does not fill the graph.
  let built = $derived.by(() => {
    if (samples.length < 2) return null;
    const peak = Math.max(...samples, 1024);
    const step = WIDTH / (samples.length - 1);
    const points = samples.map((value, i) => ({
      x: i * step,
      y: height - (value / peak) * (height - 6) - 3,
    }));
    const line = smoothPath(points);
    const last = points[points.length - 1];
    return {
      line,
      area: `${line}L${WIDTH},${height}L0,${height}Z`,
      head: last,
      live: samples[samples.length - 1] > 0,
    };
  });

  const uid = Math.random().toString(36).slice(2, 9);
  const gradientId = `spark-fill-${uid}`;
  const glowId = `spark-glow-${uid}`;
</script>

<svg
  class="spark"
  viewBox="0 0 {WIDTH} {height}"
  preserveAspectRatio="none"
  style="height: {height}px"
  aria-hidden="true"
>
  <defs>
    <linearGradient id={gradientId} x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color={color} stop-opacity="0.38" />
      <stop offset="65%" stop-color={color} stop-opacity="0.06" />
      <stop offset="100%" stop-color={color} stop-opacity="0" />
    </linearGradient>
    <filter id={glowId} x="-40%" y="-120%" width="180%" height="340%">
      <feGaussianBlur stdDeviation="2.4" result="blur" />
      <feMerge>
        <feMergeNode in="blur" />
        <feMergeNode in="SourceGraphic" />
      </feMerge>
    </filter>
  </defs>
  {#if built}
    <path d={built.area} fill="url(#{gradientId})" />
    <path
      d={built.line}
      fill="none"
      stroke={color}
      stroke-width="2"
      stroke-linejoin="round"
      stroke-linecap="round"
      filter="url(#{glowId})"
    />
    {#if built.live}
      <circle class="head" cx={built.head.x} cy={built.head.y} r="2.6" fill={color} />
      <circle class="head-ring" cx={built.head.x} cy={built.head.y} r="2.6" fill="none" stroke={color} />
    {/if}
  {/if}
</svg>

<style>
  .spark {
    display: block;
    width: 100%;
  }

  .spark path {
    transition: d 0.5s cubic-bezier(0.22, 0.61, 0.36, 1);
  }

  .head {
    transition: cx 0.5s cubic-bezier(0.22, 0.61, 0.36, 1), cy 0.5s cubic-bezier(0.22, 0.61, 0.36, 1);
  }

  .head-ring {
    transition: cx 0.5s cubic-bezier(0.22, 0.61, 0.36, 1), cy 0.5s cubic-bezier(0.22, 0.61, 0.36, 1);
    animation: ring 1.8s ease-out infinite;
    transform-box: fill-box;
    transform-origin: center;
  }

  @keyframes ring {
    0% { stroke-width: 2; opacity: 0.7; transform: scale(1); }
    100% { stroke-width: 0; opacity: 0; transform: scale(3.2); }
  }
</style>
