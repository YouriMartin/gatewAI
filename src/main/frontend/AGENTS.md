# Dashboard (Svelte + Vite) — agent instructions

Scoped to `src/main/frontend/`. The root [`AGENTS.md`](../../../AGENTS.md) still applies.

- **Svelte 5 + TypeScript + Vite 6**, Node >= 24. No UI framework, no router, no state library:
  keep it that way unless asked.
- `vite build` writes straight into `../../../target/classes/static`, so the SPA ships inside
  the jar. `./mvnw package` builds it (profile `frontend`); `./mvnw test` never does.
- Dev loop: backend on `:8080`, then `npm run dev` here (Vite proxies `/v1` → `:8080`).
- All API calls go through `src/lib/api.ts`: typed interfaces mirroring the JSON the backend
  returns (snake_case fields, as sent), Bearer API key entered by the user. A backend DTO
  change means updating the matching interface here in the same change.
- Charts are hand-rolled SVG (`Sparkline.svelte`) — no charting dependency.
- A zero CO2 figure may mean "excluded from scope", not "measured zero": always render the
  `emissions_scope` / scope notes the API sends next to the number.
- Before handing back: `npm run lint` (Biome: 2 spaces, 100 cols, organised imports) and
  `npm run check` (svelte-check).
