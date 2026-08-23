<script lang="ts">
  import type { Settings } from "../types";

  interface Props {
    settings: Settings;
    version: string;
    onChange: (settings: Settings) => void;
  }

  let { settings, version, onChange }: Props = $props();

  function toggle(key: "autoReconnect" | "connectOnLaunch" | "minimiseToTray") {
    onChange({ ...settings, [key]: !settings[key] });
  }
</script>

<div class="settings">
  <section class="card">
    <h2>Behaviour</h2>

    <button class="row" onclick={() => toggle("connectOnLaunch")}>
      <span class="row-main">
        <span class="row-title">Connect on launch</span>
        <span class="row-sub">Start the last used server when the app opens.</span>
      </span>
      <span class="switch" class:on={settings.connectOnLaunch}></span>
    </button>

    <button class="row" onclick={() => toggle("autoReconnect")}>
      <span class="row-main">
        <span class="row-title">Reconnect automatically</span>
        <span class="row-sub">The tunnel retries on its own after a drop.</span>
      </span>
      <span class="switch" class:on={settings.autoReconnect}></span>
    </button>

    <button class="row" onclick={() => toggle("minimiseToTray")}>
      <span class="row-main">
        <span class="row-title">Close to tray</span>
        <span class="row-sub">Closing the window leaves the tunnel running.</span>
      </span>
      <span class="switch" class:on={settings.minimiseToTray}></span>
    </button>
  </section>

  <section class="card">
    <h2>Appearance</h2>
    <label class="theme">
      <span class="row-title">Theme</span>
      <select
        value={settings.theme}
        onchange={(e) =>
          onChange({ ...settings, theme: (e.currentTarget as HTMLSelectElement).value as Settings["theme"] })}
      >
        <option value="system">Match system</option>
        <option value="dark">Dark</option>
        <option value="light">Light</option>
      </select>
    </label>
  </section>

  <section class="card about">
    <h2>About</h2>
    <p>
      Slipstream {version} — a client for the
      <strong>slipstream</strong> DNS tunnel. Traffic is exposed as a local SOCKS5 proxy;
      point applications at it to send them through the tunnel.
    </p>
    <p class="muted">
      Servers, certificates and proxy credentials are stored on this device only.
    </p>
  </section>
</div>

<style>
  .settings {
    display: flex;
    flex-direction: column;
    gap: 14px;
  }

  .card {
    background: var(--bg-elevated);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: 6px 18px 14px;
  }

  h2 {
    margin: 14px 0 4px;
    font-size: 12px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--text-faint);
  }

  .row {
    display: flex;
    align-items: center;
    gap: 14px;
    width: 100%;
    padding: 12px 0;
    text-align: left;
    border-bottom: 1px solid var(--border);
  }

  .row:last-child { border-bottom: none; }

  .row-main {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .row-title { font-size: 14px; }

  .row-sub {
    font-size: 12px;
    color: var(--text-muted);
  }

  .switch {
    margin-left: auto;
    flex: none;
    width: 38px;
    height: 22px;
    border-radius: 99px;
    background: var(--bg-inset);
    border: 1px solid var(--border-strong);
    position: relative;
    transition: background 0.18s, border-color 0.18s;
  }

  .switch::after {
    content: "";
    position: absolute;
    top: 2px;
    left: 2px;
    width: 16px;
    height: 16px;
    border-radius: 50%;
    background: var(--text-faint);
    transition: transform 0.18s, background 0.18s;
  }

  .switch.on {
    background: var(--accent-soft);
    border-color: var(--accent);
  }

  .switch.on::after {
    transform: translateX(16px);
    background: var(--accent);
  }

  .theme {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 12px 0;
  }

  .theme select {
    margin-left: auto;
    width: 170px;
  }

  .about p {
    margin: 8px 0;
    font-size: 13px;
    color: var(--text-muted);
    line-height: 1.6;
  }

  .about strong { color: var(--text); }

  .muted { color: var(--text-faint) !important; font-size: 12px !important; }
</style>
