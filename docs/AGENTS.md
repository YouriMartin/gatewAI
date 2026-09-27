# Documentation — agent instructions

Scoped to `docs/`. The root [`AGENTS.md`](../AGENTS.md) still applies.

- English only. `developpment/` is spelled that way on purpose.
- Where things go:
  - `technical/` — how it works and why; `functional/` — for users and integrators;
    `developpment/` — plans, roadmaps and the [progress log](developpment/progress.md).
  - A structuring decision → a new ADR in `technical/adr/` (`NNNN-kebab-title.md`, next free
    number, *Context → Decision → Consequences*), plus a row in `technical/adr/README.md`.
  - "The plan said X, the code does Y" → `decisions.md`, newest first.
  - A new page → link it from `docs/README.md`.
- Honesty rules (the project's recurring review pass):
  - Every external figure carries its source and a read-date.
  - Say *measured* only for what was measured here; otherwise *modelled*, *vendor-published*
    or *assumed*. Never let a zero read as "measured zero" when it means "not accounted".
  - Quote numbers from a real run and say which run; do not round away the caveat.
- When code changes behaviour, fix the doc that describes it in the same change —
  `api-reference.md` and `limitations.md` are the usual drift points.
