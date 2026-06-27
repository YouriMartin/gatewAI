<script lang="ts">
  import {
    fetchGreenReport,
    listClients,
    createClient,
    revokeClient,
    type GreenReport,
    type ApiClientView,
  } from './lib/api';

  const STORAGE_KEY = 'gatewai.apiKey';

  let apiKey = $state(localStorage.getItem(STORAGE_KEY) ?? '');
  let status = $state<'idle' | 'loading' | 'ok' | 'error'>('idle');
  let report = $state<GreenReport | null>(null);
  let error = $state('');

  let clients = $state<ApiClientView[]>([]);
  let adminError = $state('');
  let newName = $state('');
  let newAdmin = $state(false);
  let createdKey = $state<string | null>(null);

  function lastThirtyDays(): { from: string; to: string } {
    const to = new Date();
    const from = new Date(to.getTime() - 30 * 24 * 60 * 60 * 1000);
    return { from: from.toISOString(), to: to.toISOString() };
  }

  function message(e: unknown): string {
    return e instanceof Error ? e.message : String(e);
  }

  async function connect() {
    localStorage.setItem(STORAGE_KEY, apiKey);
    status = 'loading';
    error = '';
    try {
      const { from, to } = lastThirtyDays();
      report = await fetchGreenReport(apiKey, from, to);
      status = 'ok';
      await loadClients();
    } catch (e) {
      error = message(e);
      status = 'error';
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
  {/if}
</main>
