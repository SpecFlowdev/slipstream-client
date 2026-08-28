<script lang="ts">
  import { open } from "@tauri-apps/plugin-dialog";
  import QRCode from "qrcode";
  import jsQR from "jsqr";
  import type { Profile } from "../types";
  import { blankProfile } from "../types";
  import { readCertFile, shareProfile, importProfile } from "../ipc";
  import { t } from "../i18n.svelte";

  interface Props {
    profiles: Profile[];
    editing: Profile | null;
    onEdit: (profile: Profile | null) => void;
    onSave: (profile: Profile) => Promise<string | null>;
    onDelete: (id: string) => void;
    onImported: (profile: Profile) => void;
  }

  let { profiles, editing, onEdit, onSave, onDelete, onImported }: Props = $props();

  let draft = $state<Profile>(blankProfile());
  let error = $state<string | null>(null);
  let confirmingDelete = $state<string | null>(null);

  // Reset the form whenever a different profile is opened.
  $effect(() => {
    draft = editing ? { ...editing } : blankProfile();
    error = null;
  });

  async function pickCert() {
    const chosen = await open({
      multiple: false,
      filters: [{ name: "Certificate", extensions: ["pem", "crt", "cer"] }],
    });
    if (typeof chosen !== "string") return;
    try {
      draft.cert = await readCertFile(chosen);
      error = null;
    } catch (err) {
      error = String(err);
    }
  }

  async function submit(event: SubmitEvent) {
    event.preventDefault();
    error = await onSave({ ...draft, listenPort: Number(draft.listenPort) });
  }

  // — Share: turns a saved profile into a link, and a QR code of that link.
  // The link carries the server's settings only, never this device's local
  // port or SOCKS credentials — share.rs never puts them in it.
  let sharing = $state<Profile | null>(null);
  let shareLink = $state("");
  let shareQr = $state("");
  let shareCopied = $state(false);

  async function openShare(profile: Profile) {
    shareCopied = false;
    shareQr = "";
    shareLink = await shareProfile(profile.id);
    sharing = profile;
    // After the await above so the dialog itself appears without waiting on
    // the (slightly slower) image encode.
    shareQr = await QRCode.toDataURL(shareLink, { margin: 1, width: 288 });
  }

  function closeShare() {
    sharing = null;
  }

  async function copyShareLink() {
    await navigator.clipboard.writeText(shareLink);
    shareCopied = true;
  }

  // — Import: the other direction. A link can be typed, pasted, or lifted out
  // of a photograph of a QR code — this client has no camera flow of its own,
  // so a picture is the practical way to bring in a code shown on a phone.
  let importing = $state(false);
  let importText = $state("");
  let importError = $state<string | null>(null);
  let importResult = $state<{ profile: Profile; note: string | null } | null>(null);
  let importBusy = $state(false);

  function openImport() {
    importText = "";
    importError = null;
    importResult = null;
    importing = true;
  }

  function closeImport() {
    importing = false;
  }

  async function submitImport() {
    if (!importText.trim()) return;
    importBusy = true;
    importError = null;
    try {
      const result = await importProfile(importText.trim());
      importResult = result;
      onImported(result.profile);
    } catch (err) {
      importError = String(err);
    } finally {
      importBusy = false;
    }
  }

  async function decodeImage(event: Event) {
    const input = event.currentTarget as HTMLInputElement;
    const file = input.files?.[0];
    input.value = "";
    if (!file) return;
    importError = null;
    try {
      const bitmap = await createImageBitmap(file);
      const canvas = document.createElement("canvas");
      canvas.width = bitmap.width;
      canvas.height = bitmap.height;
      const ctx = canvas.getContext("2d");
      if (!ctx) throw new Error("Canvas is unavailable");
      ctx.drawImage(bitmap, 0, 0);
      const image = ctx.getImageData(0, 0, canvas.width, canvas.height);
      const found = jsQR(image.data, image.width, image.height);
      if (!found) {
        importError = t("profiles.importNoCode");
        return;
      }
      importText = found.data;
    } catch (err) {
      importError = String(err);
    }
  }
</script>

{#if editing !== null}
  <form class="card editor" onsubmit={submit}>
    <h2>{draft.id ? t("profiles.editTitle") : t("profiles.newTitle")}</h2>

    <label>
      <span>{t("profiles.name")}</span>
      <input bind:value={draft.name} placeholder={t("profiles.namePlaceholder")} required />
    </label>

    <label>
      <span>{t("profiles.domain")}</span>
      <input bind:value={draft.domain} placeholder="t.example.com" spellcheck="false" required />
      <small>{t("profiles.domainHint")}</small>
    </label>

    <label>
      <span>{t("profiles.resolver")}</span>
      <input bind:value={draft.resolver} placeholder="1.1.1.1:53" spellcheck="false" required />
      <small>{t("profiles.resolverHint")}</small>
    </label>

    <div class="row">
      <label>
        <span>{t("profiles.port")}</span>
        <input type="number" min="1" max="65535" bind:value={draft.listenPort} required />
      </label>
      <label>
        <span>{t("profiles.username")}</span>
        <input bind:value={draft.socksUsername} placeholder="slipstream" spellcheck="false" />
      </label>
      <label>
        <span>{t("profiles.password")}</span>
        <input type="password" bind:value={draft.socksPassword} />
      </label>
    </div>

    <label>
      <span>{t("profiles.cert")}</span>
      <div class="cert-bar">
        <button type="button" class="ghost" onclick={pickCert}>{t("profiles.certChoose")}</button>
        {#if draft.cert}
          <span class="pill ok">{t("profiles.certPinned")}</span>
          <button type="button" class="link" onclick={() => (draft.cert = "")}>{t("profiles.certRemove")}</button>
        {:else}
          <span class="pill warn">{t("profiles.certNotPinned")}</span>
        {/if}
      </div>
      <textarea
        bind:value={draft.cert}
        placeholder={t("profiles.certPastePlaceholder")}
        spellcheck="false"
        rows="6"
      ></textarea>
      <small>{t("profiles.certHint")}</small>
    </label>

    <div class="section">
      <h3>{t("profiles.tuning")}</h3>
      <small>{t("profiles.tuningHint")}</small>
    </div>

    <label>
      <span>{t("profiles.congestion")}</span>
      <select bind:value={draft.congestionControl}>
        <option value="bbr">{t("profiles.congestionBbr")}</option>
        <option value="dcubic">{t("profiles.congestionCubic")}</option>
      </select>
      <small>{t("profiles.congestionHint")}</small>
    </label>

    <button type="button" class="toggle-row" onclick={() => (draft.gso = !draft.gso)}>
      <span class="toggle-main">
        <span class="toggle-title">{t("profiles.gso")}</span>
        <span class="toggle-sub">{t("profiles.gsoHint")}</span>
      </span>
      <span class="switch" class:on={draft.gso}></span>
    </button>

    <div class="row">
      <label>
        <span>{t("profiles.keepAlive")}</span>
        <input type="number" min="100" max="60000" step="50" bind:value={draft.keepAliveMs} required />
        <small>{t("profiles.keepAliveHint")}</small>
      </label>
      <label>
        <span>{t("profiles.authoritative")} <em>({t("profiles.optional")})</em></span>
        <input bind:value={draft.authoritative} placeholder="203.0.113.9:53" spellcheck="false" />
        <small>{t("profiles.authoritativeHint")}</small>
      </label>
    </div>

    {#if error}
      <p class="error">{error}</p>
    {/if}

    <div class="actions">
      <button type="button" class="ghost" onclick={() => onEdit(null)}>{t("profiles.cancel")}</button>
      <button type="submit" class="primary">{t("profiles.save")}</button>
    </div>
  </form>
{:else}
  <div class="list">
    <div class="list-head">
      <h2>{t("profiles.title")}</h2>
      <div class="list-head-actions">
        <button class="ghost small" onclick={openImport}>{t("profiles.import")}</button>
        <button class="primary small" onclick={() => onEdit(blankProfile())}>{t("profiles.add")}</button>
      </div>
    </div>

    {#if profiles.length === 0}
      <p class="none">{t("profiles.none")}</p>
    {:else}
      {#each profiles as profile (profile.id)}
        <div class="card item">
          <div class="item-main">
            <span class="item-name">{profile.name}</span>
            <span class="item-meta">{profile.domain} · via {profile.resolver}</span>
            <span class="item-meta">
              SOCKS5 on 127.0.0.1:{profile.listenPort}
              {#if !profile.cert}<span class="unpinned"> · {t("profiles.notPinned")}</span>{/if}
            </span>
          </div>
          <div class="item-actions">
            <button class="ghost" onclick={() => openShare(profile)}>{t("profiles.share")}</button>
            <button class="ghost" onclick={() => onEdit(profile)}>{t("profiles.edit")}</button>
            {#if confirmingDelete === profile.id}
              <button class="ghost destructive" onclick={() => { onDelete(profile.id); confirmingDelete = null; }}>
                {t("profiles.confirm")}
              </button>
            {:else}
              <button class="ghost destructive" onclick={() => (confirmingDelete = profile.id)}>
                {t("profiles.delete")}
              </button>
            {/if}
          </div>
        </div>
      {/each}
    {/if}
  </div>
{/if}

{#if sharing}
  <div class="overlay" role="presentation" onclick={closeShare}>
    <div
      class="card dialog"
      role="dialog"
      aria-modal="true"
      tabindex="-1"
      onclick={(e) => e.stopPropagation()}
      onkeydown={(e) => e.key === "Escape" && closeShare()}
    >
      <h2>{t("profiles.shareTitle")}</h2>
      <p class="dialog-name">{sharing.name}</p>
      <div class="qr-box">
        {#if shareQr}
          <img src={shareQr} alt={t("profiles.shareTitle")} width="288" height="288" />
        {:else}
          <div class="qr-placeholder"></div>
        {/if}
      </div>
      <small>{t("profiles.shareHint")}</small>
      <div class="link-row">
        <code class="link-text">{shareLink}</code>
        <button type="button" class="ghost" onclick={copyShareLink}>
          {shareCopied ? t("profiles.shareCopied") : t("profiles.shareCopy")}
        </button>
      </div>
      <div class="actions">
        <button type="button" class="primary" onclick={closeShare}>{t("profiles.shareClose")}</button>
      </div>
    </div>
  </div>
{/if}

{#if importing}
  <div class="overlay" role="presentation" onclick={closeImport}>
    <div
      class="card dialog"
      role="dialog"
      aria-modal="true"
      tabindex="-1"
      onclick={(e) => e.stopPropagation()}
      onkeydown={(e) => e.key === "Escape" && closeImport()}
    >
      {#if importResult}
        <h2>{t("profiles.importSuccess")}</h2>
        <p class="dialog-name">{importResult.profile.name}</p>
        {#if importResult.note}
          <p class="note">{importResult.note}</p>
        {/if}
        <div class="actions">
          <button type="button" class="primary" onclick={closeImport}>{t("profiles.importDone")}</button>
        </div>
      {:else}
        <h2>{t("profiles.importTitle")}</h2>
        <small>{t("profiles.importHint")}</small>
        <textarea
          bind:value={importText}
          placeholder={t("profiles.importPlaceholder")}
          spellcheck="false"
          rows="3"
        ></textarea>
        <label class="pick-image">
          <span class="ghost as-label">{t("profiles.importFromImage")}</span>
          <input type="file" accept="image/*" onchange={decodeImage} />
        </label>
        {#if importError}
          <p class="error">{importError}</p>
        {/if}
        <div class="actions">
          <button type="button" class="ghost" onclick={closeImport}>{t("profiles.importCancel")}</button>
          <button
            type="button"
            class="primary"
            disabled={importBusy || !importText.trim()}
            onclick={submitImport}
          >
            {t("profiles.importSubmit")}
          </button>
        </div>
      {/if}
    </div>
  </div>
{/if}

<style>
  .card {
    background: var(--bg-elevated);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    box-shadow: var(--shadow);
  }

  .list {
    display: flex;
    flex-direction: column;
    gap: 12px;
  }

  .list-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  h2 {
    margin: 0;
    font-size: 16px;
  }

  .none {
    color: var(--text-faint);
    font-size: 13px;
  }

  .item {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 14px 16px;
    transition: transform 0.2s ease, border-color 0.2s ease;
  }

  .item:hover {
    transform: translateY(-1px);
    border-color: var(--border-strong);
  }

  .item-main {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  .item-name {
    font-weight: 600;
  }

  .item-meta {
    font-family: var(--mono);
    font-size: 11.5px;
    color: var(--text-muted);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .unpinned {
    color: var(--warn);
  }

  .item-actions {
    margin-left: auto;
    display: flex;
    gap: 6px;
    flex: none;
  }

  .editor {
    display: flex;
    flex-direction: column;
    gap: 14px;
    padding: 20px;
  }

  label {
    display: flex;
    flex-direction: column;
    gap: 6px;
  }

  label > span {
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--text-faint);
  }

  small {
    font-size: 11.5px;
    color: var(--text-faint);
    line-height: 1.45;
  }

  .row {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
    gap: 12px;
  }

  .cert-bar {
    display: flex;
    align-items: center;
    gap: 9px;
    flex-wrap: wrap;
  }

  /* Divides the connection details above from the tuning below, so the
     editor reads as two groups rather than one long run of fields. */
  .section {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding-top: 14px;
    border-top: 1px solid var(--border);
  }

  h3 {
    margin: 0;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.07em;
    color: var(--text-faint);
  }

  label > span em {
    font-style: normal;
    text-transform: none;
    letter-spacing: 0;
    opacity: 0.75;
  }

  .toggle-row {
    display: flex;
    align-items: center;
    gap: 14px;
    text-align: left;
    padding: 0;
  }

  .toggle-main {
    display: flex;
    flex-direction: column;
    gap: 3px;
    min-width: 0;
  }

  .toggle-title {
    font-size: 13.5px;
  }

  .toggle-sub {
    font-size: 11.5px;
    color: var(--text-faint);
    line-height: 1.45;
  }

  .switch {
    position: relative;
    flex: none;
    width: 38px;
    height: 21px;
    border-radius: 99px;
    background: var(--bg-inset);
    border: 1px solid var(--border-strong);
    transition: background 0.18s, border-color 0.18s;
  }

  .switch::after {
    content: "";
    position: absolute;
    top: 2px;
    left: 2px;
    width: 15px;
    height: 15px;
    border-radius: 50%;
    background: var(--text-faint);
    transition: transform 0.18s, background 0.18s;
  }

  .switch.on {
    background: var(--accent-soft);
    border-color: var(--accent);
  }

  .switch.on::after {
    transform: translateX(17px);
    background: var(--accent);
  }

  textarea {
    font-family: var(--mono);
    font-size: 12px;
    line-height: 1.5;
    resize: vertical;
    min-height: 90px;
  }

  .pill {
    font-size: 11px;
    padding: 3px 9px;
    border-radius: 99px;
    font-weight: 600;
  }

  .pill.ok {
    background: var(--success-soft);
    color: var(--success);
  }

  .pill.warn {
    background: var(--danger-soft);
    color: var(--warn);
  }

  .error {
    margin: 0;
    padding: 9px 11px;
    border-radius: var(--radius-sm);
    background: var(--danger-soft);
    color: var(--danger);
    font-size: 13px;
  }

  .actions {
    display: flex;
    justify-content: flex-end;
    gap: 8px;
  }

  .primary {
    background: linear-gradient(135deg, var(--accent), var(--accent-strong));
    color: #04121d;
    font-weight: 650;
    padding: 9px 18px;
    border-radius: var(--radius-sm);
  }

  .primary:hover { filter: brightness(1.08); }

  .primary.small { padding: 7px 14px; font-size: 13px; }

  .ghost {
    border: 1px solid var(--border-strong);
    padding: 7px 13px;
    border-radius: var(--radius-sm);
    color: var(--text-muted);
    font-size: 13px;
    transition: color 0.15s, border-color 0.15s;
  }

  .ghost:hover {
    color: var(--text);
    border-color: var(--accent);
  }

  .ghost.destructive:hover {
    color: var(--danger);
    border-color: var(--danger);
  }

  .link {
    color: var(--text-muted);
    font-size: 12px;
    text-decoration: underline;
  }

  .link:hover { color: var(--text); }

  .list-head-actions {
    display: flex;
    gap: 8px;
  }

  .ghost.small {
    padding: 7px 13px;
    font-size: 13px;
  }

  /* Share and import share one dialog chrome: a dimmed backdrop and a card
     centred over it, dismissible by clicking outside. */
  .overlay {
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.55);
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 24px;
    z-index: 20;
  }

  .dialog {
    display: flex;
    flex-direction: column;
    gap: 12px;
    padding: 22px;
    width: min(360px, 100%);
    max-height: 90vh;
    overflow-y: auto;
  }

  .dialog h2 {
    margin: 0;
    font-size: 16px;
  }

  .dialog-name {
    margin: -6px 0 0;
    font-size: 13px;
    color: var(--text-muted);
  }

  .qr-box {
    display: flex;
    align-items: center;
    justify-content: center;
    padding: 12px;
    border-radius: var(--radius-sm);
    background: #ffffff;
  }

  .qr-box img {
    display: block;
    width: 100%;
    max-width: 288px;
    height: auto;
  }

  .qr-placeholder {
    width: 288px;
    max-width: 100%;
    aspect-ratio: 1;
  }

  .link-row {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .link-text {
    flex: 1;
    min-width: 0;
    padding: 8px 10px;
    border-radius: var(--radius-sm);
    background: var(--bg-inset);
    border: 1px solid var(--border);
    font-family: var(--mono);
    font-size: 11px;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .note {
    margin: 0;
    padding: 9px 11px;
    border-radius: var(--radius-sm);
    background: var(--accent-soft);
    color: var(--text);
    font-size: 12.5px;
    line-height: 1.45;
  }

  .pick-image {
    display: block;
  }

  .pick-image .as-label {
    display: inline-block;
    cursor: pointer;
  }

  /* The native file input is unstyleable and redundant next to the ghost
     button standing in for it, so it takes no space of its own — the label
     wrapping it still forwards clicks and keyboard activation to it. */
  .pick-image input[type="file"] {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip: rect(0, 0, 0, 0);
    white-space: nowrap;
  }
</style>
