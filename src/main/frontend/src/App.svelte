<script lang="ts">
  import { fetchGreenReport, type GreenReport } from './lib/api';

  const STORAGE_KEY = 'gatewai.apiKey';

  let apiKey = $state(localStorage.getItem(STORAGE_KEY) ?? '');
  let status = $state<'idle' | 'loading' | 'ok' | 'error'>('idle');
  let report = $state<GreenReport | null>(null);
  let error = $state('');

  function lastThirtyDays(): { from: string; to: string } {
    const to = new Date();
    const from = new Date(to.getTime() - 30 * 24 * 60 * 60 * 1000);
    return { from: from.toISOString(), to: to.toISOString() };
  }

  async function connect() {
    localStorage.setItem(STORAGE_KEY, apiKey);
    status = 'loading';
    error = '';
    try {
      const { from, to } = lastThirtyDays();
      report = await fetchGreenReport(apiKey, from, to);
      status = 'ok';
    } catch (e) {
      error = e instanceof Error ? e.message : String(e);
      status = 'error';
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
  {/if}
</main>
