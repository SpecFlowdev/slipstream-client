<script lang="ts">
  import { open } from "@tauri-apps/plugin-dialog";
  import { convertFileSrc } from "@tauri-apps/api/core";
  import type { Settings } from "../types";
  import { t } from "../i18n.svelte";
  import * as ipc from "../ipc";

  interface Props {
    settings: Settings;
    version: string;
    onChange: (settings: Settings) => void;
  }

  let { settings, version, onChange }: Props = $props();

  let wallpaperError = $state<string | null>(null);

  function toggle(key: "autoReconnect" | "connectOnLaunch" | "minimiseToTray" | "killSwitch") {
    onChange({ ...settings, [key]: !settings[key] });
  }

  const switches = [
    { key: "connectOnLaunch", title: "settings.connectOnLaunch", sub: "settings.connectOnLaunchSub" },
    { key: "autoReconnect", title: "settings.autoReconnect", sub: "settings.autoReconnectSub" },
    { key: "minimiseToTray", title: "settings.tray", sub: "settings.traySub" },
    { key: "killSwitch", title: "settings.killSwitch", sub: "settings.killSwitchSub" },
  ] as const;

  let wallpaperPreview = $derived(
    settings.wallpaperPath ? convertFileSrc(settings.wallpaperPath) : null,
  );

  async function pickWallpaper() {
    const chosen = await open({
      multiple: false,
      filters: [{ name: "Image", extensions: ["png", "jpg", "jpeg", "webp", "gif", "bmp"] }],
    });
    if (typeof chosen !== "string") return;
    try {
      const path = await ipc.setWallpaper(chosen);
      wallpaperError = null;
      onChange({ ...settings, wallpaperPath: path });
    } catch (err) {
      wallpaperError = String(err);
    }
  }

  async function removeWallpaper() {
    await ipc.clearWallpaper();
    onChange({ ...settings, wallpaperPath: null });
  }
</script>

<div class="settings">
  <section class="card">
    <h2>{t("settings.behaviour")}</h2>

    {#each switches as row (row.key)}
      <button class="row" onclick={() => toggle(row.key)}>
        <span class="row-main">
          <span class="row-title">{t(row.title)}</span>
          <span class="row-sub">{t(row.sub)}</span>
        </span>
        <span class="switch" class:on={settings[row.key]}></span>
      </button>
    {/each}
  </section>

  <section class="card">
    <h2>{t("settings.appearance")}</h2>

    <label class="choice">
      <span class="row-title">{t("settings.theme")}</span>
      <select
        value={settings.theme}
        onchange={(e) =>
          onChange({
            ...settings,
            theme: (e.currentTarget as HTMLSelectElement).value as Settings["theme"],
          })}
      >
        <option value="system">{t("settings.themeSystem")}</option>
        <option value="dark">{t("settings.themeDark")}</option>
        <option value="light">{t("settings.themeLight")}</option>
        <option value="blue">{t("settings.themeBlue")}</option>
      </select>
    </label>

    <label class="choice">
      <span class="row-title">{t("settings.language")}</span>
      <select
        value={settings.language}
        onchange={(e) =>
          onChange({
            ...settings,
            language: (e.currentTarget as HTMLSelectElement).value as Settings["language"],
          })}
      >
        <option value="system">{t("settings.languageSystem")}</option>
        <option value="en">English</option>
        <option value="ru">Русский</option>
      </select>
    </label>

    {#if settings.theme === "dark"}
      <div class="wallpaper">
        <div class="wallpaper-main">
          <span class="row-title">{t("settings.wallpaper")}</span>
          <span class="row-sub">{t("settings.wallpaperHint")}</span>
        </div>
        {#if wallpaperPreview}
          <img class="thumb" src={wallpaperPreview} alt="" />
        {:else}
          <div class="thumb thumb-empty">{t("settings.wallpaperNone")}</div>
        {/if}
        <div class="wallpaper-actions">
          <button class="ghost" onclick={pickWallpaper}>{t("settings.wallpaperChoose")}</button>
          {#if settings.wallpaperPath}
            <button class="ghost" onclick={removeWallpaper}>{t("settings.wallpaperRemove")}</button>
          {/if}
        </div>
      </div>
      {#if wallpaperError}
        <p class="error">{wallpaperError}</p>
      {/if}
    {/if}
  </section>

  <section class="card about">
    <h2>{t("settings.about")}</h2>
    <p>Slipstream {version} — {t("settings.aboutBody")}</p>
    <p class="muted">{t("settings.aboutPrivacy")}</p>
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

  .choice {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 12px 0;
    border-bottom: 1px solid var(--border);
  }

  .choice:last-child { border-bottom: none; }

  .choice select {
    margin-left: auto;
    width: 170px;
  }

  .wallpaper {
    display: flex;
    align-items: center;
    gap: 14px;
    padding: 14px 0;
  }

  .wallpaper-main {
    display: flex;
    flex-direction: column;
    gap: 2px;
    flex: 1;
    min-width: 0;
  }

  .thumb {
    width: 56px;
    height: 40px;
    border-radius: var(--radius-sm);
    border: 1px solid var(--border-strong);
    object-fit: cover;
    flex: none;
  }

  .thumb-empty {
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 10px;
    color: var(--text-faint);
    text-align: center;
    padding: 2px;
    background: var(--bg-inset);
  }

  .wallpaper-actions {
    display: flex;
    gap: 6px;
    flex: none;
  }

  .ghost {
    border: 1px solid var(--border-strong);
    padding: 7px 13px;
    border-radius: var(--radius-sm);
    color: var(--text-muted);
    font-size: 12.5px;
    transition: color 0.15s, border-color 0.15s;
  }

  .ghost:hover {
    color: var(--text);
    border-color: var(--accent);
  }

  .error {
    margin: 0 0 12px;
    padding: 9px 11px;
    border-radius: var(--radius-sm);
    background: var(--danger-soft);
    color: var(--danger);
    font-size: 13px;
  }

  .about p {
    margin: 8px 0;
    font-size: 13px;
    color: var(--text-muted);
    line-height: 1.6;
  }

  .muted { color: var(--text-faint) !important; font-size: 12px !important; }
</style>
