# Dashboard guide

The dashboard is a lightweight single-page app served by the gateway itself at
<http://localhost:8080/>. It is a thin client over the same `/v1` API — anything
it shows, you can also get via the API.

## Connecting

At the top you enter your **API key** and click **Test connection**. The key is
kept in the browser (local storage) and sent as a Bearer token on every call. A
successful connection shows `Connected ✓` with the number of requests over the
last 30 days; a failure shows the error.

> Trade-off: holding the key in the browser is acceptable for a self-hosted
> internal tool. See [`functional-choices.md`](functional-choices.md).

What you see next depends on your key's role: the **metrics** sections work with
any valid key; the **admin** sections (API keys, routing config) require an
**admin** key and otherwise show an "admin key required" message.

## KPI cards (last 30 days)

Three headline numbers for the trailing 30 days:

- **€ saved** — money avoided versus a premium-by-default baseline, thanks to cache
  + routing (`total_cost_avoided_eur`).
- **gCO₂ avoided** — emissions avoided versus that same baseline
  (`total_grams_co2_avoided`).
- **Cache hit rate** — share of requests served from the semantic cache.

These are the "headline savings" figures; treat the carbon value as directional
(see [`limitations.md`](limitations.md)).

## Trends (30 days)

Two **sparklines** showing the daily evolution over the last 30 days:

- **€ saved / day**
- **gCO₂ avoided / day**

They are backed by `GET /v1/reports/green/series` (one point per day).

## Model mix

A horizontal bar per model showing how many requests each model served over the
window — a quick read on how often the router stayed local/entry vs went premium,
and how much the cache absorbed.

## CSRD reports

A date-range picker (**From** / **To**) and two buttons, **Download CSV** and
**Download PDF**. These call `GET /v1/reports/green` with the chosen range and the
selected `format`, and download the file (fetch + Blob, so the Bearer header is
sent). Use these to feed CSR / CSRD disclosures.

## API keys (admin only)

Client/key administration:

- **Create a key** — enter a client **name**, optionally tick **admin**, click
  **Create a key**. The new key is displayed **once** ("copy it, shown only
  once") — copy it immediately.
- **Client table** — lists each client with **Name**, **Role** (admin/user) and
  **Status** (active/revoked), plus a **Revoke** action for active clients.

Revoking disables the key immediately. Only hashes of keys are stored, so a lost
key cannot be recovered — create a new one.

## Routing config (admin only)

Read and hot-tune the router without a restart:

- **Strategy** — `heuristic` (default, free) or `llm` (small-model classifier).
- **Entry threshold (chars)** — text longer than this routes at least to the entry
  tier.
- **Premium threshold (chars)** — text longer than this routes to premium.
- **Premium keywords (comma-separated)** — substrings that force the premium tier
  (e.g. `refactor, architecture, security`). The list is editable inline.

Click **Save** to apply (`PUT /v1/admin/routing`); changes take effect on the next
request. A `Saved ✓` confirmation appears.

## Notes

- All figures are scoped by your API key's client when per-client namespacing is
  on, so different keys can see different numbers.
- The dashboard is intentionally minimal (Svelte, no chart-library runtime) to
  keep the bundle small — consistent with the project's green stance.
