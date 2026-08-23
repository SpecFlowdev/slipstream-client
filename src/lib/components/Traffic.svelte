<script lang="ts">
  import type { Status } from "../types";
  import { bytes, duration, rate } from "../format";
  import { t } from "../i18n.svelte";
  import AreaChart from "./AreaChart.svelte";
  import BarList from "./BarList.svelte";

  interface Props {
    status: Status;
    upSamples: number[];
    downSamples: number[];
    animated: boolean;
  }

  let { status, upSamples, downSamples, animated }: Props = $props();

  let live = $derived(status.state === "connected" || status.state === "reconnecting");
  let traffic = $derived(status.traffic);

  /** Average over the session, which is steadier than the instant rate. */
  let averageDown = $derived(
    status.uptimeSecs > 0 ? status.bytesDown / status.uptimeSecs : 0,
  );
  let averageUp = $derived(
    status.uptimeSecs > 0 ? status.bytesUp / status.uptimeSecs : 0,
  );

  let tiles = $derived([
    { label: t("traffic.peakDown"), value: rate(status.peakRateDown), tone: "down" },
    { label: t("traffic.peakUp"), value: rate(status.peakRateUp), tone: "up" },
    { label: t("traffic.avgDown"), value: rate(averageDown), tone: "muted" },
    { label: t("traffic.avgUp"), value: rate(averageUp), tone: "muted" },
    { label: t("traffic.destinations"), value: String(traffic.distinctHosts), tone: "muted" },
    { label: t("traffic.sessionConns"), value: String(traffic.totalConnections), tone: "muted" },
  ]);
</script>

<div class="wrap">
  {#if !live && traffic.totalConnections === 0}
    <div class="card empty">
      <svg viewBox="0 0 48 48" fill="none" aria-hidden="true">
        <path d="M5 34l9-11 7 7 8-13 6 9 8-12" stroke="currentColor" stroke-width="2"
          stroke-linecap="round" stroke-linejoin="round" opacity="0.55" />
        <circle cx="43" cy="14" r="2.6" fill="currentColor" />
      </svg>
      <h2>{t("traffic.emptyTitle")}</h2>
      <p>{t("traffic.emptyBody")}</p>
    </div>
  {:else}
    <section class="card chart-card">
      <div class="chart-head">
        <div class="legend">
          <span class="key down"></span>{t("conn.download")}
          <strong class="now down">{rate(status.rateDown)}</strong>
          <span class="key up"></span>{t("conn.upload")}
          <strong class="now up">{rate(status.rateUp)}</strong>
        </div>
        <span class="window">{t("traffic.lastMinute")}</span>
      </div>
      <AreaChart down={downSamples} up={upSamples} {animated} />
      <div class="totals">
        <span>{bytes(status.bytesDown)} {t("traffic.received")}</span>
        <span class="dot-sep">·</span>
        <span>{bytes(status.bytesUp)} {t("traffic.sent")}</span>
        <span class="dot-sep">·</span>
        <span>{duration(status.uptimeSecs)} {t("traffic.online")}</span>
      </div>
    </section>

    <section class="tiles">
      {#each tiles as tile (tile.label)}
        <div class="card tile">
          <span class="tile-label">{tile.label}</span>
          <span class="tile-value" data-tone={tile.tone}>{tile.value}</span>
        </div>
      {/each}
    </section>

    <div class="split">
      <section class="card panel">
        <h2>{t("traffic.topHosts")}</h2>
        {#if traffic.topHosts.length === 0}
          <p class="none">{t("traffic.noHosts")}</p>
        {:else}
          <BarList hosts={traffic.topHosts} {animated} />
        {/if}
      </section>

      <section class="card panel">
        <h2>
          {t("traffic.liveConnections")}
          <span class="count">{traffic.connections.length}</span>
        </h2>
        {#if traffic.connections.length === 0}
          <p class="none">{t("traffic.noConnections")}</p>
        {:else}
          <div class="table">
            {#each traffic.connections.slice(0, 40) as row (row.id)}
              <div class="conn">
                <span class="conn-host" title={row.host || t("traffic.unknownHost")}>
                  {row.host || t("traffic.unknownHost")}
                  {#if row.port}<span class="port">:{row.port}</span>{/if}
                </span>
                <span class="conn-bytes down">↓ {bytes(row.bytesDown)}</span>
                <span class="conn-bytes up">↑ {bytes(row.bytesUp)}</span>
                <span class="conn-age">{duration(row.ageSecs)}</span>
              </div>
            {/each}
          </div>
        {/if}
      </section>
    </div>
  {/if}
</div>

<style>
  .wrap {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .card {
    background: var(--bg-elevated);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    box-shadow: var(--shadow);
  }

  .chart-card {
    padding: 16px 18px 12px;
    display: flex;
    flex-direction: column;
    gap: 10px;
  }

  .chart-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    flex-wrap: wrap;
  }

  .legend {
    display: flex;
    align-items: center;
    gap: 7px;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--text-faint);
    flex-wrap: wrap;
  }

  .key {
    width: 9px;
    height: 3px;
    border-radius: 99px;
    display: inline-block;
  }

  .key.down { background: var(--accent); }
  .key.up { background: var(--success); }

  .now {
    font-family: var(--mono);
    font-size: 13px;
    text-transform: none;
    letter-spacing: 0;
    margin-right: 6px;
  }

  .now.down { color: var(--accent); }
  .now.up { color: var(--success); }

  .window {
    font-size: 11px;
    color: var(--text-faint);
  }

  .totals {
    display: flex;
    gap: 7px;
    flex-wrap: wrap;
    font-size: 12px;
    color: var(--text-muted);
  }

  .dot-sep { color: var(--text-faint); }

  /* Three across, so the six tiles land as two even rows rather than
     leaving one stranded on a line of its own. */
  .tiles {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 12px;
  }

  @media (max-width: 620px) {
    .tiles {
      grid-template-columns: repeat(2, 1fr);
    }
  }

  .tile {
    padding: 12px 14px;
    display: flex;
    flex-direction: column;
    gap: 4px;
    transition: transform 0.2s ease, border-color 0.2s ease;
  }

  .tile:hover {
    transform: translateY(-1px);
    border-color: var(--border-strong);
  }

  .tile-label {
    font-size: 10.5px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--text-faint);
    /* One line each: a wrapped label makes the row of tiles uneven. */
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .tile-value {
    font-family: var(--mono);
    font-size: 16px;
    font-weight: 600;
  }

  .tile-value[data-tone="down"] { color: var(--accent); }
  .tile-value[data-tone="up"] { color: var(--success); }

  .split {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
    gap: 14px;
    align-items: start;
  }

  .panel {
    padding: 16px 18px;
    display: flex;
    flex-direction: column;
    gap: 12px;
    min-width: 0;
  }

  h2 {
    margin: 0;
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.07em;
    color: var(--text-faint);
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .count {
    font-family: var(--mono);
    font-size: 11px;
    padding: 1px 7px;
    border-radius: 99px;
    background: var(--accent-soft);
    color: var(--accent);
    letter-spacing: 0;
  }

  .none {
    margin: 0;
    font-size: 12.5px;
    color: var(--text-faint);
  }

  .table {
    display: flex;
    flex-direction: column;
    gap: 1px;
    max-height: 320px;
    overflow-y: auto;
  }

  .conn {
    display: grid;
    grid-template-columns: 1fr auto auto auto;
    align-items: baseline;
    gap: 10px;
    padding: 6px 8px;
    border-radius: var(--radius-sm);
    font-size: 12px;
  }

  .conn:nth-child(odd) {
    background: var(--bg-inset);
  }

  .conn-host {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
  }

  .port {
    color: var(--text-faint);
    font-family: var(--mono);
  }

  .conn-bytes {
    font-family: var(--mono);
    font-size: 11px;
    flex: none;
  }

  .conn-bytes.down { color: var(--accent); }
  .conn-bytes.up { color: var(--success); }

  .conn-age {
    font-family: var(--mono);
    font-size: 11px;
    color: var(--text-faint);
    flex: none;
  }

  .empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    gap: 10px;
    padding: 64px 24px;
    color: var(--text-muted);
  }

  .empty svg {
    width: 48px;
    height: 48px;
    color: var(--accent);
    filter: drop-shadow(0 0 14px var(--accent-soft));
  }

  .empty h2 {
    font-size: 17px;
    color: var(--text);
    text-transform: none;
    letter-spacing: 0;
  }

  .empty p {
    margin: 0;
    max-width: 400px;
    font-size: 13px;
  }
</style>
