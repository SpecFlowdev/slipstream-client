<script lang="ts">
  import type { Profile, Status } from "../types";
  import { bytes, duration, rate } from "../format";
  import Sparkline from "./Sparkline.svelte";

  interface Props {
    status: Status;
    profiles: Profile[];
    selectedId: string | null;
    upSamples: number[];
    downSamples: number[];
    onSelect: (id: string) => void;
    onConnect: () => void;
    onDisconnect: () => void;
    onNewProfile: () => void;
  }

  let {
    status,
    profiles,
    selectedId,
    upSamples,
    downSamples,
    onSelect,
    onConnect,
    onDisconnect,
    onNewProfile,
  }: Props = $props();

  let active = $derived(profiles.find((p) => p.id === selectedId) ?? null);
  let busy = $derived(status.state === "starting" || status.state === "connecting");
  let live = $derived(status.state === "connected" || status.state === "reconnecting");

  const LABEL: Record<Status["state"], string> = {
    disconnected: "Not connected",
    starting: "Starting",
    connecting: "Connecting",
    connected: "Connected",
    reconnecting: "Reconnecting",
    error: "Failed",
  };
</script>

<div class="wrap">
  {#if profiles.length === 0}
    <div class="empty">
      <div class="empty-icon">◇</div>
      <h2>No servers yet</h2>
      <p>Add the domain, certificate and proxy credentials your server printed when it was installed.</p>
      <button class="primary" onclick={onNewProfile}>Add a server</button>
    </div>
  {:else}
    <section class="card status-card" data-state={status.state}>
      <div class="status-head">
        <span class="dot" data-state={status.state}></span>
        <span class="state">{LABEL[status.state]}</span>
        {#if status.state === "connected"}
          <span class="uptime">{duration(status.uptimeSecs)}</span>
        {/if}
      </div>

      <label class="picker">
        <span class="picker-label">Server</span>
        <select
          value={selectedId ?? ""}
          disabled={live || busy}
          onchange={(e) => onSelect((e.currentTarget as HTMLSelectElement).value)}
        >
          {#each profiles as profile (profile.id)}
            <option value={profile.id}>{profile.name}</option>
          {/each}
        </select>
      </label>

      {#if active}
        <div class="endpoint">
          <span>{active.domain}</span>
          <span class="sep">via</span>
          <span>{active.resolver}</span>
        </div>
      {/if}

      {#if status.message}
        <p class="error">{status.message}</p>
      {/if}

      {#if live || busy}
        <button class="primary danger" onclick={onDisconnect}>Disconnect</button>
      {:else}
        <button class="primary" onclick={onConnect} disabled={!selectedId}>Connect</button>
      {/if}

      {#if live && active}
        <p class="hint">
          SOCKS5 proxy on <code>127.0.0.1:{active.listenPort}</code>
          {#if active.socksUsername}
            · sign in as <code>{active.socksUsername}</code>
          {/if}
        </p>
      {/if}
    </section>

    <section class="meters">
      <div class="card meter">
        <div class="meter-head">
          <span class="meter-name">Download</span>
          <span class="meter-rate down">{rate(status.rateDown)}</span>
        </div>
        <Sparkline samples={downSamples} color="var(--accent)" />
        <span class="meter-total">{bytes(status.bytesDown)} total</span>
      </div>

      <div class="card meter">
        <div class="meter-head">
          <span class="meter-name">Upload</span>
          <span class="meter-rate up">{rate(status.rateUp)}</span>
        </div>
        <Sparkline samples={upSamples} color="var(--success)" />
        <span class="meter-total">{bytes(status.bytesUp)} total</span>
      </div>
    </section>

    <section class="card facts">
      <div class="fact">
        <span class="fact-label">Open connections</span>
        <span class="fact-value">{status.activeConnections}</span>
      </div>
      <div class="fact">
        <span class="fact-label">Local port</span>
        <span class="fact-value">{active ? active.listenPort : "—"}</span>
      </div>
      <div class="fact">
        <span class="fact-label">Pinning</span>
        <span class="fact-value">{active && active.cert ? "On" : "Off"}</span>
      </div>
    </section>
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
    padding: 18px;
  }

  .status-card {
    display: flex;
    flex-direction: column;
    gap: 14px;
    position: relative;
    overflow: hidden;
  }

  .status-card::before {
    content: "";
    position: absolute;
    inset: 0 0 auto 0;
    height: 2px;
    background: var(--border-strong);
  }

  .status-card[data-state="connected"]::before {
    background: linear-gradient(90deg, var(--accent), var(--success));
  }

  .status-card[data-state="error"]::before {
    background: var(--danger);
  }

  .status-head {
    display: flex;
    align-items: center;
    gap: 9px;
  }

  .dot {
    width: 9px;
    height: 9px;
    border-radius: 50%;
    background: var(--text-faint);
    flex: none;
  }

  .dot[data-state="connected"] {
    background: var(--success);
    box-shadow: 0 0 0 4px var(--success-soft);
  }

  .dot[data-state="starting"],
  .dot[data-state="connecting"],
  .dot[data-state="reconnecting"] {
    background: var(--warn);
    animation: pulse 1.4s ease-in-out infinite;
  }

  .dot[data-state="error"] {
    background: var(--danger);
    box-shadow: 0 0 0 4px var(--danger-soft);
  }

  @keyframes pulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.35; }
  }

  .state {
    font-weight: 600;
    font-size: 15px;
  }

  .uptime {
    margin-left: auto;
    font-family: var(--mono);
    font-size: 12px;
    color: var(--text-muted);
  }

  .picker {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  .picker-label,
  .fact-label,
  .meter-name {
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--text-faint);
  }

  .endpoint {
    display: flex;
    align-items: center;
    gap: 7px;
    flex-wrap: wrap;
    font-family: var(--mono);
    font-size: 12px;
    color: var(--text-muted);
  }

  .sep {
    color: var(--text-faint);
  }

  .error {
    margin: 0;
    padding: 9px 11px;
    border-radius: var(--radius-sm);
    background: var(--danger-soft);
    color: var(--danger);
    font-size: 13px;
  }

  .primary {
    background: linear-gradient(135deg, var(--accent), var(--accent-strong));
    color: #04121d;
    font-weight: 650;
    padding: 11px;
    border-radius: var(--radius-sm);
    transition: filter 0.15s, opacity 0.15s;
  }

  .primary:hover:not(:disabled) {
    filter: brightness(1.08);
  }

  .primary:disabled {
    opacity: 0.45;
    cursor: not-allowed;
  }

  .primary.danger {
    background: var(--danger-soft);
    color: var(--danger);
    border: 1px solid var(--danger);
  }

  .hint {
    margin: 0;
    font-size: 12px;
    color: var(--text-muted);
  }

  code {
    font-family: var(--mono);
    background: var(--bg-inset);
    border-radius: 4px;
    padding: 1px 5px;
    color: var(--text);
  }

  .meters {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
    gap: 14px;
  }

  .meter {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 14px 16px;
  }

  .meter-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
  }

  .meter-rate {
    font-family: var(--mono);
    font-size: 16px;
    font-weight: 600;
  }

  .meter-rate.down { color: var(--accent); }
  .meter-rate.up { color: var(--success); }

  .meter-total {
    font-size: 12px;
    color: var(--text-faint);
  }

  .facts {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 12px;
    padding: 14px 16px;
  }

  .fact {
    display: flex;
    flex-direction: column;
    gap: 3px;
  }

  .fact-value {
    font-family: var(--mono);
    font-size: 15px;
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

  .empty-icon {
    font-size: 34px;
    color: var(--accent);
  }

  .empty h2 {
    margin: 0;
    font-size: 17px;
    color: var(--text);
  }

  .empty p {
    margin: 0;
    max-width: 380px;
    font-size: 13px;
  }

  .empty .primary {
    margin-top: 6px;
    padding: 10px 20px;
  }
</style>
