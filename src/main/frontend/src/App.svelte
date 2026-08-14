<script lang="ts">
import {
  type ApiClientView,
  createClient,
  type DecisionView,
  downloadReport,
  type ExplanationView,
  explainDecision,
  explainPrompt,
  fetchGreenReport,
  fetchGreenSeries,
  type GreenReport,
  getRoutingConfig,
  listClients,
  listDecisions,
  type RoutingConfig,
  revokeClient,
  updateRoutingConfig,
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

interface RouteEdit {
  name: string;
  tier: string;
  examplesText: string;
}

let routing = $state<RoutingConfig | null>(null);
let keywordsText = $state('');
let routesEdit = $state<RouteEdit[]>([]);
let routingError = $state('');
let routingSaved = $state(false);

let decisions = $state<DecisionView[]>([]);
let decisionsError = $state('');
let explanation = $state<ExplanationView | null>(null);
let explanationError = $state('');
let selectedId = $state<string | null>(null);
let explaining = $state(false);
let promptText = $state('');

let reportFrom = $state(isoDate(new Date(Date.now() - 30 * 86_400_000)));
let reportTo = $state(isoDate(new Date()));
let exportError = $state('');

function isoDate(d: Date): string {
  return d.toISOString().slice(0, 10);
}

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

async function download(format: 'csv' | 'pdf') {
  exportError = '';
  try {
    const blob = await downloadReport(
      apiKey,
      `${reportFrom}T00:00:00Z`,
      `${reportTo}T23:59:59Z`,
      format,
    );
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `green-report.${format}`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  } catch (e) {
    exportError = message(e);
  }
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
    await loadDecisions();
  } catch (e) {
    error = message(e);
    status = 'error';
  }
}

async function loadRouting() {
  try {
    routing = await getRoutingConfig(apiKey);
    keywordsText = routing.premium_keywords.join(', ');
    routesEdit = routing.routes.map((r) => ({
      name: r.name,
      tier: r.tier,
      examplesText: r.examples.join('\n'),
    }));
    routingError = '';
  } catch (e) {
    routing = null;
    routingError = message(e);
  }
}

function addRoute() {
  routesEdit = [...routesEdit, { name: '', tier: 'local', examplesText: '' }];
}

function removeRoute(index: number) {
  routesEdit = routesEdit.filter((_, i) => i !== index);
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
  const routes = routesEdit.map((r) => ({
    name: r.name.trim(),
    tier: r.tier,
    examples: r.examplesText
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line.length > 0),
  }));
  try {
    routing = await updateRoutingConfig(apiKey, {
      ...routing,
      premium_keywords: keywords,
      routes,
    });
    keywordsText = routing.premium_keywords.join(', ');
    routesEdit = routing.routes.map((r) => ({
      name: r.name,
      tier: r.tier,
      examplesText: r.examples.join('\n'),
    }));
    routingSaved = true;
    routingError = '';
  } catch (e) {
    routingError = message(e);
  }
}

async function loadDecisions() {
  try {
    decisions = await listDecisions(apiKey, 20);
    decisionsError = '';
  } catch (e) {
    decisions = [];
    decisionsError = message(e);
  }
}

async function explainRow(decision: DecisionView) {
  if (!decision.correlationId) {
    return;
  }
  selectedId = decision.correlationId;
  explaining = true;
  explanationError = '';
  try {
    explanation = await explainDecision(apiKey, decision.correlationId);
  } catch (e) {
    explanation = null;
    explanationError = message(e);
  } finally {
    explaining = false;
  }
}

async function explainText() {
  if (!promptText.trim()) {
    return;
  }
  selectedId = null;
  explaining = true;
  explanationError = '';
  try {
    explanation = await explainPrompt(apiKey, promptText);
  } catch (e) {
    explanation = null;
    explanationError = message(e);
  } finally {
    explaining = false;
  }
}

// Why an analysis is missing, in words rather than an enum name. Each of these
// is an answer, not a failure — see docs/technical/attribution.md.
function statusNote(status: string): string {
  switch (status) {
    case 'PROMPT_UNAVAILABLE':
      return 'Only prompt hashes are stored, so a past request cannot be re-embedded. Paste the prompt below to analyse it against the current rules.';
    case 'NOT_APPLICABLE_STRATEGY':
      return 'The active strategy does not decide by similarity, so there is no similarity to decompose.';
    case 'NO_ROUTES_CONFIGURED':
      return 'No semantic route is configured.';
    case 'EMPTY_PROMPT':
      return 'Nothing to analyse.';
    case 'NO_ALTERNATIVE_TIER':
      return 'Every configured route leads to the tier that won — no wording would have changed the outcome.';
    default:
      return '';
  }
}

function num(value: number | null | undefined, digits = 3): string {
  return value === null || value === undefined ? '—' : value.toFixed(digits);
}

function shortId(id: string | null): string {
  return id ? id.slice(0, 8) : '—';
}

function clockTime(iso: string): string {
  return new Date(iso).toLocaleTimeString();
}

// Shares are already normalized over the positive contributions; a negative
// contribution took no share and gets no bar.
function shareWidth(share: number): number {
  return Math.max(0, Math.min(1, share)) * 100;
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
    <p class="subtitle">Carbon footprint dashboard</p>
  </header>

  <section class="connect">
    <label for="api-key">API key</label>
    <input
      id="api-key"
      type="password"
      bind:value={apiKey}
      placeholder="Bearer token"
      autocomplete="off"
    />
    <button onclick={connect} disabled={!apiKey || status === 'loading'}>
      {status === 'loading' ? 'Connecting…' : 'Test connection'}
    </button>
  </section>

  {#if status === 'error'}
    <p class="error">Connection failed: {error}</p>
  {/if}

  {#if status === 'ok' && report}
    <p class="ok">
      Connected ✓ — {report.total_requests} request(s) over the last 30 days.
    </p>
    <section class="cards">
      <div class="card">
        <span class="label">€ saved</span>
        <strong>{report.total_cost_avoided_eur.toFixed(4)}</strong>
      </div>
      <div class="card">
        <span class="label">gCO₂ avoided</span>
        <strong>{report.total_grams_co2_avoided.toFixed(1)}</strong>
      </div>
      <div class="card">
        <span class="label">Cache hit rate</span>
        <strong>{(report.cache_hit_rate * 100).toFixed(1)}%</strong>
      </div>
    </section>

    {#if series.length > 0}
      <section class="trends">
        <h2>Trends (30 days)</h2>
        <div class="cards">
          <div class="card">
            <span class="label">€ saved / day</span>
            <Sparkline values={series.map((p) => p.total_cost_avoided_eur)} />
          </div>
          <div class="card">
            <span class="label">gCO₂ avoided / day</span>
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
        <h2>Model mix</h2>
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

    <section class="trends">
      <h2>CSRD reports</h2>
      {#if exportError}
        <p class="error">Export failed: {exportError}</p>
      {/if}
      <div class="export">
        <label>
          From
          <input type="date" bind:value={reportFrom} />
        </label>
        <label>
          To
          <input type="date" bind:value={reportTo} />
        </label>
        <button onclick={() => download('csv')}>Download CSV</button>
        <button onclick={() => download('pdf')}>Download PDF</button>
      </div>
    </section>

    <section class="admin">
      <h2>Why this decision</h2>

      {#if decisionsError}
        <p class="error">
          Decision trace unavailable: {decisionsError} (admin key required)
        </p>
      {/if}

      <p class="hint">
        The last 20 requests, as decided. Click one for its confidence and
        provenance; paste a prompt below to see which words carry a routing
        decision and where it would have gone instead.
      </p>

      {#if decisions.length > 0}
        <table class="decisions">
          <thead>
            <tr>
              <th>Time</th><th>Request</th><th>Cache</th><th>Tier</th>
              <th>Reason</th><th>Margin</th>
            </tr>
          </thead>
          <tbody>
            {#each decisions as decision (decision.correlationId ?? decision.at)}
              <tr
                class:selected={decision.correlationId === selectedId}
                onclick={() => explainRow(decision)}
              >
                <td>{clockTime(decision.at)}</td>
                <td><code>{shortId(decision.correlationId)}</code></td>
                <td>{decision.cache?.outcome ?? '—'}</td>
                <td>{decision.routing?.chosenTier ?? 'served from cache'}</td>
                <td>{decision.routing?.decisionReason ?? '—'}</td>
                <td>{num(decision.routing?.confidence?.margin ?? null)}</td>
              </tr>
            {/each}
          </tbody>
        </table>
      {/if}

      <div class="explain-prompt">
        <textarea
          rows="3"
          bind:value={promptText}
          placeholder="Paste a prompt to explain it against the rules in force now"
        ></textarea>
        <button onclick={explainText} disabled={!promptText.trim() || explaining}>
          {explaining ? 'Explaining…' : 'Explain this prompt'}
        </button>
      </div>

      {#if explanationError}
        <p class="error">Explanation failed: {explanationError}</p>
      {/if}

      {#if explanation}
        <div class="explanation">
          {#if explanation.decision?.routing}
            {@const routing = explanation.decision.routing}
            <h3>Decision</h3>
            <p class="detail">
              <strong>{routing.chosenTier}</strong>
              {routing.chosenModelId ? ` → ${routing.chosenModelId}` : ''} ·
              {routing.decisionReason} ·
              {routing.strategy === routing.effectiveStrategy
                ? routing.strategy
                : `${routing.strategy} → ${routing.effectiveStrategy}`}
              {routing.escalatedTo ? ` · escalated to ${routing.escalatedTo}` : ''}
              · {routing.routingLatencyMs} ms
            </p>
            <p class="detail">
              top {num(routing.confidence.topScore)} ·
              margin {num(routing.confidence.margin)} ·
              threshold {num(routing.confidence.threshold)} ·
              set {routing.confidence.conformalSet
                ? routing.confidence.conformalSet.join(', ') || '(empty)'
                : 'not calibrated'}
            </p>
          {/if}

          {#if explanation.decision?.cache}
            {@const cache = explanation.decision.cache}
            <h3>Cache</h3>
            <p class="detail">
              {cache.outcome} · similarity {num(cache.similarityScore)} ·
              runner-up {num(cache.runnerUpScore)} ·
              threshold {num(cache.threshold)} ·
              {cache.conformalStatus ?? 'not calibrated'}
              {cache.originCorrelationId
                ? ` · answer written by ${shortId(cache.originCorrelationId)}`
                : ''}
            </p>
          {/if}

          <h3>What carried the match</h3>
          {#if explanation.attribution.status === 'COMPUTED'}
            <p class="detail">
              route <strong>{explanation.attribution.route}</strong> ·
              «{explanation.attribution.matchedUtterance}» ·
              similarity {num(explanation.attribution.similarity)}
            </p>
            <div class="segments">
              {#each explanation.attribution.segments as segment (segment.rank)}
                <div class="segment-row">
                  <span class="segment-text">{segment.segment}</span>
                  <div class="segment-bar">
                    <div
                      class="segment-fill"
                      class:negative={segment.contribution < 0}
                      style={`width: ${shareWidth(segment.share)}%`}
                    ></div>
                  </div>
                  <span class="segment-value">
                    {segment.contribution >= 0 ? '+' : ''}{num(segment.contribution)}
                  </span>
                </div>
              {/each}
            </div>
          {:else}
            <p class="hint">{statusNote(explanation.attribution.status)}</p>
          {/if}

          <h3>Where it would have gone instead</h3>
          {#if explanation.counterfactuals.status === 'COMPUTED'}
            <ul class="counterfactuals">
              {#each explanation.counterfactuals.alternatives as alt (alt.rank)}
                <li>
                  <strong>{alt.tier}</strong> — had it looked more like
                  «{alt.nearestUtterance}»
                  <span class="gap">(missed by {num(alt.delta)})</span>
                </li>
              {/each}
            </ul>
          {:else}
            <p class="hint">{statusNote(explanation.counterfactuals.status)}</p>
          {/if}

          <p class="provenance">
            {explanation.provenance.embeddingModelVersion ?? 'no embedding model'}
            · rules {explanation.provenance.routingConfigVersion ?? 'n/a'}
            · calibration {explanation.provenance.status ?? 'n/a'}
            {explanation.provenance.calibrationDate
              ? ` (${explanation.provenance.calibrationDate.slice(0, 10)})`
              : ''}
          </p>
        </div>
      {/if}
    </section>

    <section class="admin">
      <h2>API keys</h2>

      {#if adminError}
        <p class="error">Admin unavailable: {adminError} (admin key required)</p>
      {/if}

      {#if createdKey}
        <p class="ok created-key">
          Key created (copy it, shown only once):
          <code>{createdKey}</code>
        </p>
      {/if}

      <div class="create">
        <input bind:value={newName} placeholder="Client name" />
        <label class="checkbox">
          <input type="checkbox" bind:checked={newAdmin} /> admin
        </label>
        <button onclick={create} disabled={!newName}>Create a key</button>
      </div>

      {#if clients.length > 0}
        <table>
          <thead>
            <tr><th>Name</th><th>Role</th><th>Status</th><th></th></tr>
          </thead>
          <tbody>
            {#each clients as client (client.id)}
              <tr class:disabled={!client.enabled}>
                <td>{client.name}</td>
                <td>{client.admin ? 'admin' : 'user'}</td>
                <td>{client.enabled ? 'active' : 'revoked'}</td>
                <td>
                  {#if client.enabled}
                    <button class="link" onclick={() => revoke(client.id)}>
                      Revoke
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
      <h2>Routing config</h2>

      {#if routingError}
        <p class="error">Config unavailable: {routingError} (admin key required)</p>
      {/if}

      {#if routing}
        <div class="routing-form">
          <label>
            Strategy
            <select bind:value={routing.strategy}>
              <option value="embedding">embedding (semantic routes)</option>
              <option value="cascade">cascade (routes, model on ambiguity)</option>
              <option value="heuristic">heuristic</option>
              <option value="llm">llm</option>
            </select>
          </label>
          <label>
            Entry threshold (chars)
            <input
              type="number"
              min="0"
              bind:value={routing.entry_length_threshold}
            />
          </label>
          <label>
            Premium threshold (chars)
            <input
              type="number"
              min="0"
              bind:value={routing.premium_length_threshold}
            />
          </label>
          <label class="wide">
            Premium keywords (comma-separated, heuristic fallback)
            <input type="text" bind:value={keywordsText} />
          </label>

          <div class="routes wide">
            <h3>Semantic routes</h3>
            <p class="hint">
              Each route sends matching requests to its tier. Describe the
              route with example prompts (one per line, any language) — a
              request follows the route whose examples are semantically
              closest. Below the similarity threshold, the heuristic decides.
            </p>
            <label class="threshold">
              Similarity threshold (0–1)
              <input
                type="number"
                min="0"
                max="1"
                step="0.01"
                bind:value={routing.route_similarity_threshold}
              />
            </label>
            <label class="threshold">
              Cascade ambiguity band (0–1)
              <input
                type="number"
                min="0"
                max="1"
                step="0.01"
                bind:value={routing.cascade_margin_band}
              />
            </label>
            <p class="hint">
              Cascade only: when the top two routes are within this band, the
              classifier model decides. Wider means more escalations, so more
              accuracy on ambiguous prompts and more model calls. It is not part
              of the routing config version, so tuning it does not invalidate a
              conformal calibration.
            </p>
            {#each routesEdit as route, i (i)}
              <div class="route-card">
                <div class="route-head">
                  <input
                    bind:value={route.name}
                    placeholder="Route name (e.g. code-and-analysis)"
                  />
                  <select bind:value={route.tier}>
                    <option value="local">local</option>
                    <option value="cloud_entry">cloud_entry</option>
                    <option value="cloud_premium">cloud_premium</option>
                  </select>
                  <button class="link" onclick={() => removeRoute(i)}>
                    Remove
                  </button>
                </div>
                <textarea
                  rows="4"
                  bind:value={route.examplesText}
                  placeholder="Example prompts, one per line"
                ></textarea>
              </div>
            {/each}
            <button class="link" onclick={addRoute}>+ Add a route</button>
          </div>

          <div class="actions">
            <button onclick={saveRouting}>Save</button>
            {#if routingSaved}<span class="ok">Saved ✓</span>{/if}
          </div>
        </div>
      {/if}
    </section>
  {/if}
</main>
