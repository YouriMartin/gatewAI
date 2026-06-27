<script lang="ts">
  import {
    fetchGreenReport,
    fetchGreenSeries,
    listClients,
    createClient,
    revokeClient,
    getRoutingConfig,
    updateRoutingConfig,
    type GreenReport,
    type ApiClientView,
    type RoutingConfig,
  } from './lib/api';
  import Sparkline from './lib/Sparkline.svelte';

  const STORAGE_KEY = 'gatewai.apiKey';

  let apiKey = $state(localStorage.getItem(STORAGE_KEY) ?? '');
  let status = $state<'idle' | 'loading' | 'ok' | 'error'>('idle');
  let report = $state<GreenReport | null>(null);
  let series = $state<GreenReport[]>([]);
  let error = $state('');

  let clients = $state<ApiClientView[]>([]);
  let adminError = $state('');
  let newName = $state('');
  let newAdmin = $state(false);
  let createdKey = $state<string | null>(null);

  let routing = $state<RoutingConfig | null>(null);
  let keywordsText = $state('');
  let routingError = $state('');
  let routingSaved = $state(false);

  function lastThirtyDays(): { from: string; to: string } {
    const to = new Date();
    const from = new Date(to.getTime() - 30 * 24 * 60 * 60 * 1000);
    return { from: from.toISOString(), to: to.toISOString() };
  }

  function message(e: unknown): string {
    return e instanceof Error ? e.message : String(e);
  }

  function barWidth(count: number): number {
    if (!report) {
      return 0;
    }
    const max = Math.max(...Object.values(report.model_mix), 1);
    return (count / max) * 100;
  }

  async function connect() {
    localStorage.setItem(STORAGE_KEY, apiKey);
    status = 'loading';
    error = '';
    try {
      const { from, to } = lastThirtyDays();
      report = await fetchGreenReport(apiKey, from, to);
      series = await fetchGreenSeries(apiKey, from, to);
      status = 'ok';
      await loadClients();
      await loadRouting();
    } catch (e) {
      error = message(e);
      status = 'error';
    }
  }

  async function loadRouting() {
    try {
      routing = await getRoutingConfig(apiKey);
      keywordsText = routing.premium_keywords.join(', ');
      routingError = '';
    } catch (e) {
      routing = null;
      routingError = message(e);
    }
  }

  async function saveRouting() {
    if (!routing) {
      return;
    }
    routingSaved = false;
    const keywords = keywordsText
      .split(',')
      .map((k) => k.trim())
      .filter((k) => k.length > 0);
    try {
      routing = await updateRoutingConfig(apiKey, {
        ...routing,
        premium_keywords: keywords,
      });
      keywordsText = routing.premium_keywords.join(', ');
      routingSaved = true;
      routingError = '';
    } catch (e) {
      routingError = message(e);
    }
  }

  async function loadClients() {
    try {
      clients = await listClients(apiKey);
      adminError = '';
    } catch (e) {
      clients = [];
      adminError = message(e);
    }
  }

  async function create() {
    createdKey = null;
    try {
      const created = await createClient(apiKey, newName, newAdmin);
      createdKey = created.api_key;
      newName = '';
      newAdmin = false;
      await loadClients();
    } catch (e) {
      adminError = message(e);
    }
  }

  async function revoke(id: string) {
    try {
      await revokeClient(apiKey, id);
      await loadClients();
    } catch (e) {
      adminError = message(e);
    }
  }
</script>

<main>
  <header>
    <h1>🌱 Green AI Proxy</h1>
    <p class="subtitle">Dashboard d'empreinte carbone</p>
  </header>

  <section class="connect">
    <label for="api-key">Clé API</label>
    <input
      id="api-key"
      type="password"
      bind:value={apiKey}
      placeholder="Bearer token"
      autocomplete="off"
    />
    <button onclick={connect} disabled={!apiKey || status === 'loading'}>
      {status === 'loading' ? 'Connexion…' : 'Tester la connexion'}
    </button>
  </section>

  {#if status === 'error'}
    <p class="error">Échec de connexion : {error}</p>
  {/if}

  {#if status === 'ok' && report}
    <p class="ok">
      Connecté ✓ — {report.total_requests} requête(s) sur les 30 derniers jours.
    </p>
    <section class="cards">
      <div class="card">
        <span class="label">€ économisés</span>
        <strong>{report.total_cost_avoided_eur.toFixed(4)}</strong>
      </div>
      <div class="card">
        <span class="label">gCO₂ évités</span>
        <strong>{report.total_grams_co2_avoided.toFixed(1)}</strong>
      </div>
      <div class="card">
        <span class="label">Taux de hit cache</span>
        <strong>{(report.cache_hit_rate * 100).toFixed(1)}%</strong>
      </div>
    </section>

    {#if series.length > 0}
      <section class="trends">
        <h2>Tendances (30 jours)</h2>
        <div class="cards">
          <div class="card">
            <span class="label">€ économisés / jour</span>
            <Sparkline values={series.map((p) => p.total_cost_avoided_eur)} />
          </div>
          <div class="card">
            <span class="label">gCO₂ évités / jour</span>
            <Sparkline
              values={series.map((p) => p.total_grams_co2_avoided)}
              color="#58a6ff"
            />
          </div>
        </div>
      </section>
    {/if}

    {#if Object.keys(report.model_mix).length > 0}
      <section class="trends">
        <h2>Répartition des modèles</h2>
        <div class="mix">
          {#each Object.entries(report.model_mix) as [model, count] (model)}
            <div class="mix-row">
              <span class="mix-name">{model}</span>
              <div class="mix-bar">
                <div class="mix-fill" style={`width: ${barWidth(count)}%`}></div>
              </div>
              <span class="mix-count">{count}</span>
            </div>
          {/each}
        </div>
      </section>
    {/if}

    <section class="admin">
      <h2>Clés API</h2>

      {#if adminError}
        <p class="error">Admin indisponible : {adminError} (clé admin requise)</p>
      {/if}

      {#if createdKey}
        <p class="ok created-key">
          Clé créée (copie-la, affichée une seule fois) :
          <code>{createdKey}</code>
        </p>
      {/if}

      <div class="create">
        <input bind:value={newName} placeholder="Nom du client" />
        <label class="checkbox">
          <input type="checkbox" bind:checked={newAdmin} /> admin
        </label>
        <button onclick={create} disabled={!newName}>Créer une clé</button>
      </div>

      {#if clients.length > 0}
        <table>
          <thead>
            <tr><th>Nom</th><th>Rôle</th><th>État</th><th></th></tr>
          </thead>
          <tbody>
            {#each clients as client (client.id)}
              <tr class:disabled={!client.enabled}>
                <td>{client.name}</td>
                <td>{client.admin ? 'admin' : 'user'}</td>
                <td>{client.enabled ? 'actif' : 'révoqué'}</td>
                <td>
                  {#if client.enabled}
                    <button class="link" onclick={() => revoke(client.id)}>
                      Révoquer
                    </button>
                  {/if}
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      {/if}
    </section>

    <section class="admin">
      <h2>Config du routage</h2>

      {#if routingError}
        <p class="error">Config indisponible : {routingError} (clé admin requise)</p>
      {/if}

      {#if routing}
        <div class="routing-form">
          <label>
            Stratégie
            <select bind:value={routing.strategy}>
              <option value="heuristic">heuristic</option>
              <option value="llm">llm</option>
            </select>
          </label>
          <label>
            Seuil entrée (chars)
            <input
              type="number"
              min="0"
              bind:value={routing.entry_length_threshold}
            />
          </label>
          <label>
            Seuil premium (chars)
            <input
              type="number"
              min="0"
              bind:value={routing.premium_length_threshold}
            />
          </label>
          <label class="wide">
            Mots-clés premium (séparés par des virgules)
            <input type="text" bind:value={keywordsText} />
          </label>
          <div class="actions">
            <button onclick={saveRouting}>Enregistrer</button>
            {#if routingSaved}<span class="ok">Enregistré ✓</span>{/if}
          </div>
        </div>
      {/if}
    </section>
  {/if}
</main>
