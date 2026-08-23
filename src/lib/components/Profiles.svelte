<script lang="ts">
  import { open } from "@tauri-apps/plugin-dialog";
  import type { Profile } from "../types";
  import { blankProfile } from "../types";
  import { readCertFile } from "../ipc";

  interface Props {
    profiles: Profile[];
    editing: Profile | null;
    onEdit: (profile: Profile | null) => void;
    onSave: (profile: Profile) => Promise<string | null>;
    onDelete: (id: string) => void;
  }

  let { profiles, editing, onEdit, onSave, onDelete }: Props = $props();

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
</script>

{#if editing !== null}
  <form class="card editor" onsubmit={submit}>
    <h2>{draft.id ? "Edit server" : "New server"}</h2>

    <label>
      <span>Name</span>
      <input bind:value={draft.name} placeholder="Home server" required />
    </label>

    <label>
      <span>Tunnel domain</span>
      <input bind:value={draft.domain} placeholder="t.example.com" spellcheck="false" required />
      <small>The delegated zone your server answers for.</small>
    </label>

    <label>
      <span>Resolver</span>
      <input bind:value={draft.resolver} placeholder="1.1.1.1:53" spellcheck="false" required />
      <small>Where queries are sent — your provider's resolver, or the server itself.</small>
    </label>

    <div class="row">
      <label>
        <span>Local SOCKS5 port</span>
        <input type="number" min="1" max="65535" bind:value={draft.listenPort} required />
      </label>
      <label>
        <span>Proxy username</span>
        <input bind:value={draft.socksUsername} placeholder="slipstream" spellcheck="false" />
      </label>
      <label>
        <span>Proxy password</span>
        <input type="password" bind:value={draft.socksPassword} />
      </label>
    </div>

    <label>
      <span>Server certificate</span>
      <div class="cert">
        <button type="button" class="ghost" onclick={pickCert}>Choose cert.pem…</button>
        {#if draft.cert}
          <span class="pill ok">Pinned</span>
          <button type="button" class="link" onclick={() => (draft.cert = "")}>Remove</button>
        {:else}
          <span class="pill warn">Not pinned</span>
        {/if}
      </div>
      <small>
        Without a certificate the server is not verified, so anyone able to answer your DNS
        queries can impersonate it.
      </small>
    </label>

    {#if error}
      <p class="error">{error}</p>
    {/if}

    <div class="actions">
      <button type="button" class="ghost" onclick={() => onEdit(null)}>Cancel</button>
      <button type="submit" class="primary">Save</button>
    </div>
  </form>
{:else}
  <div class="list">
    <div class="list-head">
      <h2>Servers</h2>
      <button class="primary small" onclick={() => onEdit(blankProfile())}>Add server</button>
    </div>

    {#if profiles.length === 0}
      <p class="none">Nothing here yet.</p>
    {:else}
      {#each profiles as profile (profile.id)}
        <div class="card item">
          <div class="item-main">
            <span class="item-name">{profile.name}</span>
            <span class="item-meta">{profile.domain} · via {profile.resolver}</span>
            <span class="item-meta">
              SOCKS5 on 127.0.0.1:{profile.listenPort}
              {#if !profile.cert}<span class="unpinned"> · not pinned</span>{/if}
            </span>
          </div>
          <div class="item-actions">
            <button class="ghost" onclick={() => onEdit(profile)}>Edit</button>
            {#if confirmingDelete === profile.id}
              <button class="ghost destructive" onclick={() => { onDelete(profile.id); confirmingDelete = null; }}>
                Confirm
              </button>
            {:else}
              <button class="ghost destructive" onclick={() => (confirmingDelete = profile.id)}>
                Delete
              </button>
            {/if}
          </div>
        </div>
      {/each}
    {/if}
  </div>
{/if}

<style>
  .card {
    background: var(--bg-elevated);
    border: 1px solid var(--border);
    border-radius: var(--radius);
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

  .cert {
    display: flex;
    align-items: center;
    gap: 9px;
    flex-wrap: wrap;
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
</style>
