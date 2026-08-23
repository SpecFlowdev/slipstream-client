<script lang="ts">
  import { rate } from "../format";

  interface Props {
    /** Newest sample last, bytes per second. */
    down: number[];
    up: number[];
    /** Seconds covered by the whole window, for the time axis. */
    windowSecs?: number;
    animated?: boolean;
  }

  let { down, up, windowSecs = 60, animated = true }: Props = $props();

  const W = 600;
  const H = 190;
  const PAD_L = 52;
  const PAD_R = 10;
  const PAD_T = 12;
  const PAD_B = 22;

  const plotW = W - PAD_L - PAD_R;
  const plotH = H - PAD_T - PAD_B;

  /** Catmull-Rom through the points, as cubic Beziers. */
  function smooth(points: { x: number; y: number }[]): string {
    if (points.length < 2) return "";
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

  /**
   * A round number at or above the busiest sample, so the axis reads in whole
   * units and the line does not touch the ceiling. Floored well above zero so
   * an idle tunnel shows a flat line rather than magnified noise.
   */
  let ceiling = $derived.by(() => {
    const busiest = Math.max(...down, ...up, 0);
    const floor = 64 * 1024;
    if (busiest <= floor) return floor;
    // Next power of two above the peak, with 15% headroom.
    return 2 ** Math.ceil(Math.log2(busiest * 1.15));
  });

  function series(samples: number[]) {
    if (samples.length < 2) return { line: "", area: "", head: null };
    const step = plotW / (samples.length - 1);
    const points = samples.map((value, i) => ({
      x: PAD_L + i * step,
      y: PAD_T + plotH - Math.min(1, value / ceiling) * plotH,
    }));
    const line = smooth(points);
    return {
      line,
      area: `${line}L${PAD_L + plotW},${PAD_T + plotH}L${PAD_L},${PAD_T + plotH}Z`,
      head: points[points.length - 1],
    };
  }

  let downSeries = $derived(series(down));
  let upSeries = $derived(series(up));

  // Four gridlines including zero, labelled in the same units as the readout.
  let gridlines = $derived(
    [0, 0.25, 0.5, 0.75, 1].map((fraction) => ({
      y: PAD_T + plotH - fraction * plotH,
      label: fraction === 0 ? "0" : rate(ceiling * fraction),
    })),
  );

  let ticks = $derived(
    [0, 0.25, 0.5, 0.75, 1].map((fraction) => ({
      x: PAD_L + fraction * plotW,
      label: fraction === 1 ? "now" : `-${Math.round(windowSecs * (1 - fraction))}s`,
    })),
  );

  const uid = Math.random().toString(36).slice(2, 9);
</script>

<div class="chart">
  <svg viewBox="0 0 {W} {H}" preserveAspectRatio="none" role="img" aria-label="Throughput over the last minute">
    <defs>
      <linearGradient id="down-{uid}" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="var(--accent)" stop-opacity="0.42" />
        <stop offset="70%" stop-color="var(--accent)" stop-opacity="0.05" />
        <stop offset="100%" stop-color="var(--accent)" stop-opacity="0" />
      </linearGradient>
      <linearGradient id="up-{uid}" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" stop-color="var(--success)" stop-opacity="0.34" />
        <stop offset="70%" stop-color="var(--success)" stop-opacity="0.04" />
        <stop offset="100%" stop-color="var(--success)" stop-opacity="0" />
      </linearGradient>
      <filter id="glow-{uid}" x="-20%" y="-60%" width="140%" height="240%">
        <feGaussianBlur stdDeviation="2.2" result="b" />
        <feMerge><feMergeNode in="b" /><feMergeNode in="SourceGraphic" /></feMerge>
      </filter>
    </defs>

    {#each gridlines as line (line.label)}
      <line class="grid" x1={PAD_L} y1={line.y} x2={W - PAD_R} y2={line.y} />
      <text class="axis" x={PAD_L - 8} y={line.y + 3.5} text-anchor="end">{line.label}</text>
    {/each}

    {#each ticks as tick (tick.label)}
      <text class="axis" x={tick.x} y={H - 6} text-anchor="middle">{tick.label}</text>
    {/each}

    {#if downSeries.area}
      <path d={downSeries.area} fill="url(#down-{uid})" />
      <path class="line" class:animated d={downSeries.line} stroke="var(--accent)" filter="url(#glow-{uid})" />
    {/if}
    {#if upSeries.area}
      <path d={upSeries.area} fill="url(#up-{uid})" />
      <path class="line" class:animated d={upSeries.line} stroke="var(--success)" filter="url(#glow-{uid})" />
    {/if}

    {#if downSeries.head}
      <circle class="head" class:animated cx={downSeries.head.x} cy={downSeries.head.y} r="3" fill="var(--accent)" />
    {/if}
    {#if upSeries.head}
      <circle class="head" class:animated cx={upSeries.head.x} cy={upSeries.head.y} r="3" fill="var(--success)" />
    {/if}
  </svg>
</div>

<style>
  .chart {
    width: 100%;
    /* The viewBox is not preserved, so a fixed height keeps the plot from
       stretching oddly at wide window sizes. */
    height: 190px;
  }

  svg {
    width: 100%;
    height: 100%;
    overflow: visible;
  }

  .grid {
    stroke: var(--border);
    stroke-width: 1;
    vector-effect: non-scaling-stroke;
  }

  .axis {
    fill: var(--text-faint);
    font-size: 9px;
    font-family: var(--mono);
  }

  .line {
    fill: none;
    stroke-width: 2;
    stroke-linejoin: round;
    stroke-linecap: round;
    vector-effect: non-scaling-stroke;
  }

  .line.animated,
  .head.animated {
    transition: d 0.55s cubic-bezier(0.22, 0.61, 0.36, 1),
      cx 0.55s cubic-bezier(0.22, 0.61, 0.36, 1),
      cy 0.55s cubic-bezier(0.22, 0.61, 0.36, 1);
  }
</style>
