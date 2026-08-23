<script lang="ts">
  import type { LogLine, LogLevel } from "../types";
  import { clockTime } from "../format";

  interface Props {
    lines: LogLine[];
    onClear: () => void;
  }

  let { lines, onClear }: Props = $props();

  let filter = $state<LogLevel | "all">("all");
  let follow = $state(true);
  let viewport = $state<HTMLDivElement | null>(null);

  const RANK: Record<LogLevel, number> = { trace: 0, debug: 1, info: 2, warn: 3, error: 4 };

  let shown = $derived(
    filter === "all" ? lines : lines.filter((l) => RANK[l.level] >= RANK[filter as LogLevel]),
  );

  // Keep the newest line in view unless the reader has scrolled away.
  $effect(() => {
    void shown.length;
    if (follow && viewport) {
      viewport.scrollTop = viewport.scrollHeight;
    }
  });

  function onScroll() {
    if (!viewport) return;
    const distance = viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight;
    follow = distance < 40;
  }
</script>

<div class="logs">
  <div class="bar">
    <div class="filters">
      {#each ["all", "info", "warn", "error"] as level (level)}
        <button
          class="chip"
          class:active={filter === level}
          onclick={() => (filter = level as LogLevel | "all")}
        >
          {level}
        </button>
      {/each}
    </div>
    <span class="count">{shown.length} lines</span>
    <button class="ghost" onclick={onClear}>Clear</button>
  </div>

  <div class="viewport" bind:this={viewport} onscroll={onScroll}>
    {#if shown.length === 0}
      <p class="none">Nothing logged yet.</p>
    {:else}
      {#each shown as line (line.seq)}
        <div class="line">
          <span class="time">{clockTime(line.ts)}</span>
          <span class="level" data-level={line.level}>{line.level}</span>
          <span class="msg">{line.message}</span>
        </div>
      {/each}
    {/if}
  </div>

  {#if !follow}
    <button
      class="jump"
      onclick={() => {
        follow = true;
        if (viewport) viewport.scrollTop = viewport.scrollHeight;
      }}
    >
      Jump to latest
    </button>
  {/if}
</div>

<style>
  .logs {
    display: flex;
    flex-direction: column;
    gap: 10px;
    height: 100%;
    min-height: 0;
    position: relative;
  }

  .bar {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .filters {
    display: flex;
    gap: 5px;
  }

  .chip {
    padding: 5px 11px;
    border-radius: 99px;
    font-size: 12px;
    color: var(--text-muted);
    border: 1px solid var(--border);
    text-transform: capitalize;
    transition: color 0.15s, border-color 0.15s, background 0.15s;
  }

  .chip:hover { color: var(--text); }

  .chip.active {
    color: var(--accent);
    border-color: var(--accent);
    background: var(--accent-soft);
  }

  .count {
    margin-left: auto;
    font-size: 12px;
    color: var(--text-faint);
  }

  .ghost {
    border: 1px solid var(--border-strong);
    padding: 5px 12px;
    border-radius: var(--radius-sm);
    color: var(--text-muted);
    font-size: 12px;
  }

  .ghost:hover { color: var(--text); border-color: var(--accent); }

  .viewport {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    background: var(--bg-inset);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 10px 12px;
    font-family: var(--mono);
    font-size: 12px;
    line-height: 1.65;
  }

  .none {
    color: var(--text-faint);
    margin: 0;
  }

  .line {
    display: flex;
    gap: 10px;
  }

  .time {
    color: var(--text-faint);
    flex: none;
  }

  .level {
    flex: none;
    width: 42px;
    text-transform: uppercase;
    font-size: 10.5px;
    letter-spacing: 0.04em;
    padding-top: 1px;
  }

  .level[data-level="info"] { color: var(--accent); }
  .level[data-level="warn"] { color: var(--warn); }
  .level[data-level="error"] { color: var(--danger); }
  .level[data-level="debug"],
  .level[data-level="trace"] { color: var(--text-faint); }

  .msg {
    color: var(--text);
    word-break: break-word;
  }

  .jump {
    position: absolute;
    bottom: 14px;
    left: 50%;
    transform: translateX(-50%);
    background: var(--accent);
    color: #04121d;
    font-size: 12px;
    font-weight: 600;
    padding: 6px 14px;
    border-radius: 99px;
    box-shadow: var(--shadow);
  }
</style>
