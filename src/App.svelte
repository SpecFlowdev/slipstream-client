<script lang="ts">
  import type { UnlistenFn } from "@tauri-apps/api/event";
  import { convertFileSrc } from "@tauri-apps/api/core";
  import Connection from "./lib/components/Connection.svelte";
  import Traffic from "./lib/components/Traffic.svelte";
  import Rules from "./lib/components/Rules.svelte";
  import Profiles from "./lib/components/Profiles.svelte";
  import Logs from "./lib/components/Logs.svelte";
  import Settings from "./lib/components/Settings.svelte";
  import * as ipc from "./lib/ipc";
  import { setLanguage, t } from "./lib/i18n.svelte";
  import { blankProfile, EMPTY_STATUS, type LogLine, type Profile, type Rule, type SessionRecord, type Settings as Prefs, type Status } from "./lib/types";

  const VERSION = "0.2.0";
  const SAMPLES = 60;

  type Tab = "connection" | "traffic" | "rules" | "servers" | "logs" | "settings";

  const TABS = [
    { id: "connection", label: "nav.connection" },
    { id: "traffic", label: "nav.traffic" },
    { id: "rules", label: "nav.rules" },
    { id: "servers", label: "nav.servers" },
    { id: "logs", label: "nav.logs" },
    { id: "settings", label: "nav.settings" },
  ] as const;

  let tab = $state<Tab>("connection");
  let profiles = $state<Profile[]>([]);
  let selectedId = $state<string | null>(null);
  let status = $state<Status>(EMPTY_STATUS);
  let logs = $state<LogLine[]>([]);
  let prefs = $state<Prefs>({
    autoReconnect: true,
    connectOnLaunch: false,
    minimiseToTray: true,
    theme: "system",
    language: "system",
    killSwitch: true,
    wallpaperPath: null,
    wallpaperDim: 45,
    wallpaperBlur: 0,
    systemProxy: false,
    animations: true,
  });
  let editing = $state<Profile | null>(null);
  let rules = $state<Rule[]>([]);
  let history = $state<SessionRecord[]>([]);

  let upSamples = $state<number[]>(new Array(SAMPLES).fill(0));
  let downSamples = $state<number[]>(new Array(SAMPLES).fill(0));

  // The tray keeps the tunnel alive without the window, so the UI always
  // reloads its state rather than assuming it started everything.
  $effect(() => {
    let stops: UnlistenFn[] = [];
    (async () => {
      profiles = await ipc.listProfiles();
      prefs = await ipc.getSettings();
      logs = await ipc.getLogs();
      rules = await ipc.listRules();
      history = await ipc.getHistory();
      status = await ipc.getStatus();
      selectedId = status.profileId ?? profiles[0]?.id ?? null;

      stops.push(
        await ipc.onStatus((next) => {
          const ended = status.state === "connected" && next.state === "disconnected";
          status = next;
          if (ended) ipc.getHistory().then((h) => (history = h));
          if (next.profileId) selectedId = next.profileId;
          upSamples = [...upSamples.slice(1), next.rateUp];
          downSamples = [...downSamples.slice(1), next.rateDown];
        }),
      );
      stops.push(
        await ipc.onLog((line) => {
          logs = logs.length >= 2000 ? [...logs.slice(1), line] : [...logs, line];
        }),
      );
    })();
    return () => stops.forEach((stop) => stop());
  });

  $effect(() => {
    setLanguage(prefs.language);
  });

  $effect(() => {
    const root = document.documentElement;
    if (prefs.theme === "system") {
      root.removeAttribute("data-theme");
    } else {
      root.setAttribute("data-theme", prefs.theme);
    }
  });

  // The wallpaper applies in every theme, not just the dark one it was
  // originally gated to — picking an image while on light or blue used to
  // save the file and then show nothing.
  $effect(() => {
    const root = document.documentElement;
    if (prefs.wallpaperPath) {
      root.style.setProperty("--wallpaper-url", `url("${convertFileSrc(prefs.wallpaperPath)}")`);
      root.style.setProperty("--wallpaper-dim", String(prefs.wallpaperDim / 100));
      root.style.setProperty("--wallpaper-blur", `${prefs.wallpaperBlur}px`);
      root.setAttribute("data-wallpaper", "on");
    } else {
      root.style.removeProperty("--wallpaper-url");
      root.style.removeProperty("--wallpaper-dim");
      root.style.removeProperty("--wallpaper-blur");
      root.removeAttribute("data-wallpaper");
    }
  });

  async function refreshProfiles() {
    profiles = await ipc.listProfiles();
    if (!profiles.some((p) => p.id === selectedId)) {
      selectedId = profiles[0]?.id ?? null;
    }
  }

  async function saveProfile(profile: Profile): Promise<string | null> {
    try {
      const saved = await ipc.saveProfile(profile);
      await refreshProfiles();
      selectedId = saved.id;
      editing = null;
      return null;
    } catch (err) {
      return String(err);
    }
  }

  async function removeProfile(id: string) {
    if (status.profileId === id) await ipc.disconnect();
    await ipc.deleteProfile(id);
    await refreshProfiles();
  }

  async function startTunnel() {
    if (!selectedId) return;
    try {
      await ipc.connect(selectedId);
    } catch (err) {
      // The backend also reports this through the status event; showing it here
      // covers failures that happen before the session exists.
      status = { ...status, state: "error", message: String(err) };
    }
  }

  async function stopTunnel() {
    await ipc.disconnect();
    upSamples = new Array(SAMPLES).fill(0);
    downSamples = new Array(SAMPLES).fill(0);
  }

  async function saveRules(next: Rule[]): Promise<string | null> {
    try {
      rules = await ipc.saveRules(next);
      return null;
    } catch (err) {
      return String(err);
    }
  }

  async function updateSettings(next: Prefs) {
    prefs = await ipc.saveSettings(next);
  }

  let connected = $derived(status.state === "connected");
</script>

<div class="shell">
  <aside class="sidebar">
    <div class="brand">
      <span class="mark" class:live={connected}></span>
      <span class="brand-name">Slipstream</span>
    </div>

    <nav>
      {#each TABS as item (item.id)}
        <button class="nav" class:active={tab === item.id} onclick={() => (tab = item.id)}>
          {#if tab === item.id}<span class="nav-bar"></span>{/if}
          <svg class="glyph" viewBox="0 0 20 20" fill="none" aria-hidden="true">
            {#if item.id === "connection"}
              <path d="M2.5 11h3l1.6-4.5 2.6 8 1.8-6 1.4 2.5h4.6" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
            {:else if item.id === "traffic"}
              <path d="M3 16.5V12M7.5 16.5V7M12 16.5v-6.5M16.5 16.5V4.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" />
            {:else if item.id === "rules"}
              <path d="M10 2.6l6.4 2.5v5.1c0 4.2-2.7 7.6-6.4 8.5-3.7-.9-6.4-4.3-6.4-8.5V5.1L10 2.6z" stroke="currentColor" stroke-width="1.5" stroke-linejoin="round" />
              <path d="M7.4 10.2l2 2 3.4-3.9" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
            {:else if item.id === "servers"}
              <rect x="3" y="3.5" width="14" height="5.5" rx="1.8" stroke="currentColor" stroke-width="1.5" />
              <rect x="3" y="11" width="14" height="5.5" rx="1.8" stroke="currentColor" stroke-width="1.5" />
              <circle cx="6.3" cy="6.25" r="1" fill="currentColor" />
              <circle cx="6.3" cy="13.75" r="1" fill="currentColor" />
            {:else if item.id === "logs"}
              <path d="M3.5 5.5h13M3.5 10h13M3.5 14.5h8.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" />
            {:else}
              <circle cx="10" cy="10" r="2.4" stroke="currentColor" stroke-width="1.5" />
              <path d="M10 3.2v2M10 14.8v2M16.8 10h-2M5.2 10h-2M15.1 4.9l-1.4 1.4M6.3 13.7l-1.4 1.4M15.1 15.1l-1.4-1.4M6.3 6.3L4.9 4.9" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" />
            {/if}
          </svg>
          <span>{t(item.label)}</span>
        </button>
      {/each}
    </nav>

    <div class="foot">
      <span class="badge" data-state={status.state}>{t(`state.${status.state}`)}</span>
    </div>
  </aside>

  <main>
    {#if tab === "connection"}
      <Connection
        {status}
        {profiles}
        {selectedId}
        {upSamples}
        {downSamples}
        onSelect={(id) => (selectedId = id)}
        onConnect={startTunnel}
        onDisconnect={stopTunnel}
        onNewProfile={() => {
          tab = "servers";
          editing = blankProfile();
        }}
      />
    {:else if tab === "traffic"}
      <Traffic
        {status}
        {upSamples}
        {downSamples}
        {history}
        animated={prefs.animations}
        onClearHistory={async () => { await ipc.clearHistory(); history = []; }}
      />
    {:else if tab === "rules"}
      <Rules {rules} blocked={status.blocked} onSave={saveRules} />
    {:else if tab === "servers"}
      <Profiles
        {profiles}
        {editing}
        onEdit={(p) => (editing = p)}
        onSave={saveProfile}
        onDelete={removeProfile}
      />
    {:else if tab === "logs"}
      <Logs lines={logs} onClear={async () => { await ipc.clearLogs(); logs = []; }} />
    {:else}
      <Settings settings={prefs} version={VERSION} onChange={updateSettings} />
    {/if}
  </main>
</div>

<style>
  .shell {
    display: grid;
    grid-template-columns: 210px 1fr;
    height: 100vh;
  }

  .sidebar {
    display: flex;
    flex-direction: column;
    gap: 18px;
    padding: 18px 12px;
    background: var(--bg-elevated);
    border-right: 1px solid var(--border);
  }

  .brand {
    display: flex;
    align-items: center;
    gap: 9px;
    padding: 0 8px;
  }

  .mark {
    width: 10px;
    height: 10px;
    border-radius: 3px;
    background: var(--text-faint);
    transition: background 0.2s, box-shadow 0.2s;
  }

  .mark.live {
    background: linear-gradient(135deg, var(--accent), var(--success));
    box-shadow: 0 0 10px var(--accent-soft);
  }

  .brand-name {
    font-weight: 650;
    letter-spacing: 0.01em;
  }

  nav {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .nav {
    position: relative;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 9px 10px;
    border-radius: var(--radius-sm);
    color: var(--text-muted);
    font-size: 13.5px;
    text-align: left;
    transition: background 0.15s, color 0.15s;
  }

  .nav:hover {
    background: var(--bg-inset);
    color: var(--text);
  }

  .nav.active {
    background: var(--accent-soft);
    color: var(--accent);
  }

  .nav-bar {
    position: absolute;
    left: -12px;
    top: 50%;
    transform: translateY(-50%);
    width: 3px;
    height: 18px;
    border-radius: 0 3px 3px 0;
    background: linear-gradient(180deg, var(--accent), var(--success));
  }

  .glyph {
    width: 16px;
    height: 16px;
    flex: none;
  }

  .foot {
    margin-top: auto;
    padding: 0 8px;
  }

  .badge {
    display: inline-block;
    font-size: 10.5px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    padding: 3px 9px;
    border-radius: 99px;
    background: var(--bg-inset);
    color: var(--text-faint);
    border: 1px solid var(--border);
  }

  .badge[data-state="connected"] {
    color: var(--success);
    border-color: var(--success);
    background: var(--success-soft);
  }

  .badge[data-state="error"] {
    color: var(--danger);
    border-color: var(--danger);
    background: var(--danger-soft);
  }

  main {
    padding: 20px;
    overflow-y: auto;
    min-height: 0;
  }
</style>
