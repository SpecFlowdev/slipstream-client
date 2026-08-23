<script lang="ts">
  import type { HostRow } from "../types";
  import { bytes } from "../format";

  interface Props {
    hosts: HostRow[];
    animated?: boolean;
  }

  let { hosts, animated = true }: Props = $props();

  // Scale against the busiest destination so the ranking is readable even
  // when one host dwarfs the rest.
  let busiest = $derived(Math.max(...hosts.map((h) => h.bytesTotal), 1));
</script>

<ul class="bars">
  {#each hosts as host (host.host)}
    <li>
      <div class="row">
        <span class="host" title={host.host}>{host.host}</span>
        <span class="total">{bytes(host.bytesTotal)}</span>
      </div>
      <div class="track">
        <!-- Down and up stacked in one bar, in the same colours the chart
             uses, so the split is readable without a legend of its own. -->
        <span
          class="fill down"
          class:animated
          style="width: {(host.bytesDown / busiest) * 100}%"
        ></span>
        <span
          class="fill up"
          class:animated
          style="width: {(host.bytesUp / busiest) * 100}%"
        ></span>
      </div>
    </li>
  {/each}
</ul>

<style>
  .bars {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: 11px;
  }

  .row {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 5px;
  }

  .host {
    font-size: 12.5px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .total {
    font-family: var(--mono);
    font-size: 11.5px;
    color: var(--text-muted);
    flex: none;
  }

  .track {
    display: flex;
    height: 6px;
    border-radius: 99px;
    background: var(--bg-inset);
    overflow: hidden;
  }

  .fill {
    height: 100%;
    min-width: 0;
  }

  .fill.animated {
    transition: width 0.5s cubic-bezier(0.22, 0.61, 0.36, 1);
  }

  .fill.down {
    background: linear-gradient(90deg, var(--accent-strong), var(--accent));
  }

  .fill.up {
    background: linear-gradient(90deg, var(--success), var(--success));
    opacity: 0.75;
  }
</style>
