<script lang="ts">
  import type { Rule } from "../types";
  import { blankRule } from "../types";
  import { t } from "../i18n.svelte";

  interface Props {
    rules: Rule[];
    blocked: number;
    onSave: (rules: Rule[]) => Promise<string | null>;
  }

  let { rules, blocked, onSave }: Props = $props();

  let draft = $state<Rule[]>([]);
  let error = $state<string | null>(null);
  let saving = $state(false);

  // Edits are made against a copy and committed together, so a half-typed
  // pattern never reaches the relay and starts blocking things.
  $effect(() => {
    draft = rules.map((r) => ({ ...r }));
  });

  let dirty = $derived(JSON.stringify(draft) !== JSON.stringify(rules));

  async function commit() {
    saving = true;
    error = await onSave(draft.map((r) => ({ ...r, pattern: r.pattern.trim() })));
    saving = false;
  }

  function add() {
    draft = [...draft, blankRule()];
  }

  function remove(index: number) {
    draft = draft.filter((_, i) => i !== index);
  }

  function move(index: number, by: number) {
    const next = index + by;
    if (next < 0 || next >= draft.length) return;
    const copy = [...draft];
    [copy[index], copy[next]] = [copy[next], copy[index]];
    draft = copy;
  }

  /** A few rules worth having, offered when the list is empty. */
  const STARTERS: { label: string; patterns: string[] }[] = [
    {
      label: "rules.starterTrackers",
      patterns: [
        "*.doubleclick.net",
        "*.googlesyndication.com",
        "*.google-analytics.com",
        "*.scorecardresearch.com",
        "*.adnxs.com",
      ],
    },
    { label: "rules.starterLocal", patterns: ["localhost", "*.local"] },
  ];

  function addStarter(patterns: string[]) {
    const have = new Set(draft.map((r) => r.pattern));
    draft = [
      ...draft,
      ...patterns
        .filter((p) => !have.has(p))
        .map((p) => ({ pattern: p, action: "block" as const, enabled: true, note: "" })),
    ];
  }
</script>

<div class="wrap">
  <section class="card intro">
    <div class="intro-main">
      <h2>{t("rules.title")}</h2>
      <p>{t("rules.intro")}</p>
    </div>
    <div class="counter" class:live={blocked > 0}>
      <span class="counter-value">{blocked}</span>
      <span class="counter-label">{t("rules.blockedCount")}</span>
    </div>
  </section>

  {#if draft.length === 0}
    <section class="card empty">
      <svg viewBox="0 0 48 48" fill="none" aria-hidden="true">
        <path d="M24 6l15 6v11c0 9.4-6.4 17-15 19-8.6-2-15-9.6-15-19V12l15-6z"
          stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" opacity="0.5" />
        <path d="M17 24l5 5 10-11" stroke="currentColor" stroke-width="2"
          stroke-linecap="round" stroke-linejoin="round" />
      </svg>
      <h3>{t("rules.emptyTitle")}</h3>
      <p>{t("rules.emptyBody")}</p>
      <div class="starters">
        {#each STARTERS as starter (starter.label)}
          <button class="ghost" onclick={() => addStarter(starter.patterns)}>
            {t(starter.label as never)}
          </button>
        {/each}
        <button class="ghost" onclick={add}>{t("rules.add")}</button>
      </div>
    </section>
  {:else}
    <section class="card list">
      <div class="head">
        <span class="col-pattern">{t("rules.pattern")}</span>
        <span class="col-action">{t("rules.action")}</span>
        <span class="col-note">{t("rules.note")}</span>
        <span></span>
      </div>

      {#each draft as rule, i (i)}
        <div class="row" class:off={!rule.enabled}>
          <input
            class="col-pattern mono"
            bind:value={rule.pattern}
            placeholder="*.example.com"
            spellcheck="false"
          />
          <select class="col-action" bind:value={rule.action}>
            <option value="block">{t("rules.block")}</option>
            <option value="allow">{t("rules.allow")}</option>
          </select>
          <input class="col-note" bind:value={rule.note} placeholder={t("rules.notePlaceholder")} />
          <div class="row-actions">
            <button
              class="icon"
              title={rule.enabled ? t("rules.disable") : t("rules.enable")}
              onclick={() => (rule.enabled = !rule.enabled)}
            >{rule.enabled ? "◉" : "○"}</button>
            <button class="icon" title={t("rules.moveUp")} onclick={() => move(i, -1)}>↑</button>
            <button class="icon" title={t("rules.moveDown")} onclick={() => move(i, 1)}>↓</button>
            <button class="icon destructive" title={t("profiles.delete")} onclick={() => remove(i)}>✕</button>
          </div>
        </div>
      {/each}

      <div class="foot">
        <button class="ghost" onclick={add}>{t("rules.add")}</button>
        <span class="order-hint">{t("rules.orderHint")}</span>
      </div>
    </section>
  {/if}

  {#if error}
    <p class="error">{error}</p>
  {/if}

  {#if dirty}
    <div class="bar">
      <span>{t("rules.unsaved")}</span>
      <button class="ghost" onclick={() => (draft = rules.map((r) => ({ ...r })))}>
        {t("profiles.cancel")}
      </button>
      <button class="primary" disabled={saving} onclick={commit}>{t("profiles.save")}</button>
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

  .intro {
    display: flex;
    align-items: center;
    gap: 18px;
    padding: 16px 18px;
  }

  .intro-main { flex: 1; min-width: 0; }

  h2 {
    margin: 0 0 4px;
    font-size: 15px;
  }

  .intro p {
    margin: 0;
    font-size: 12.5px;
    color: var(--text-muted);
    line-height: 1.5;
  }

  .counter {
    flex: none;
    text-align: right;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .counter-value {
    font-family: var(--mono);
    font-size: 22px;
    font-weight: 600;
    color: var(--text-faint);
    transition: color 0.2s;
  }

  .counter.live .counter-value { color: var(--danger); }

  .counter-label {
    font-size: 10px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--text-faint);
  }

  .list {
    padding: 14px 16px;
    display: flex;
    flex-direction: column;
    gap: 8px;
  }

  .head, .row {
    display: grid;
    grid-template-columns: minmax(0, 1.4fr) 116px minmax(0, 1fr) auto;
    gap: 8px;
    align-items: center;
  }

  .head {
    font-size: 10px;
    text-transform: uppercase;
    letter-spacing: 0.06em;
    color: var(--text-faint);
    padding: 0 2px 2px;
  }

  .row.off { opacity: 0.45; }

  .mono { font-family: var(--mono); font-size: 12.5px; }

  .row-actions {
    display: flex;
    gap: 2px;
  }

  .icon {
    width: 26px;
    height: 26px;
    border-radius: var(--radius-sm);
    color: var(--text-faint);
    font-size: 13px;
    line-height: 1;
    transition: background 0.15s, color 0.15s;
  }

  .icon:hover { background: var(--bg-inset); color: var(--text); }
  .icon.destructive:hover { color: var(--danger); }

  .foot {
    display: flex;
    align-items: center;
    gap: 12px;
    padding-top: 4px;
  }

  .order-hint {
    font-size: 11.5px;
    color: var(--text-faint);
  }

  .empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    text-align: center;
    gap: 10px;
    padding: 48px 24px;
    color: var(--text-muted);
  }

  .empty svg {
    width: 44px;
    height: 44px;
    color: var(--accent);
    filter: drop-shadow(0 0 14px var(--accent-soft));
  }

  .empty h3 {
    margin: 0;
    font-size: 16px;
    color: var(--text);
  }

  .empty p {
    margin: 0;
    max-width: 420px;
    font-size: 13px;
  }

  .starters {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
    justify-content: center;
    margin-top: 6px;
  }

  .bar {
    position: sticky;
    bottom: 0;
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 11px 14px;
    background: var(--bg-elevated);
    border: 1px solid var(--accent);
    border-radius: var(--radius);
    box-shadow: var(--shadow);
    font-size: 13px;
  }

  .bar span { flex: 1; }

  .error {
    margin: 0;
    padding: 9px 11px;
    border-radius: var(--radius-sm);
    background: var(--danger-soft);
    color: var(--danger);
    font-size: 13px;
  }

  .ghost {
    border: 1px solid var(--border-strong);
    padding: 7px 13px;
    border-radius: var(--radius-sm);
    color: var(--text-muted);
    font-size: 12.5px;
    transition: color 0.15s, border-color 0.15s;
  }

  .ghost:hover { color: var(--text); border-color: var(--accent); }

  .primary {
    background: linear-gradient(135deg, var(--accent), var(--accent-strong));
    color: #04121d;
    font-weight: 650;
    padding: 8px 18px;
    border-radius: var(--radius-sm);
  }

  .primary:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
