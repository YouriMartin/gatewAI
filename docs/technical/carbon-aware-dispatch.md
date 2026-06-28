# Carbon-aware deferred dispatch

For non-interactive workloads, a request can be submitted asynchronously and run
later at the **greenest** candidate zone. Sources:
`adapter/in/web/AsyncChatCompletionController`,
`application/service/DeferredChatService`,
`infrastructure/dispatch/{InMemoryDeferredJobStore, CarbonAwareDispatchWorker,
DispatchProperties, DispatchSchedulingConfig}`, and the domain
`CarbonAwareZoneSelector`, `CarbonZoneContext`, `DeferredJob`,
`DeferredJobStatus`.

## API

- `POST /v1/chat/completions/async` — queues the request, returns **`202
  Accepted`** with a `DeferredJobResponse` (`status = queued` + a job id).
- `GET /v1/chat/completions/async/{id}` — returns the job status; once
  `completed`, the OpenAI-shaped `result` and the `chosen_zone` are included;
  `failed` carries an `error`. Unknown id → `404`, malformed id → `400`.

These submit/poll endpoints are not rate-limited the same as the sync path (only
the submit `POST /v1/chat/completions*` is — status polls are not).

## Job lifecycle

`DeferredJob` moves through `DeferredJobStatus`: `QUEUED → RUNNING →
COMPLETED | FAILED`. Jobs live in the **`InMemoryDeferredJobStore`** — an in-memory
store, so **queued jobs do not survive a restart and are not shared across
instances** (single-node assumption; see
[`../functional/limitations.md`](../functional/limitations.md)).

## The scheduled worker

`CarbonAwareDispatchWorker` is `@ConditionalOnProperty(gatewai.dispatch.enabled =
true)` — it only exists when dispatch is enabled. Scheduling is turned on by
`DispatchSchedulingConfig`. On each tick
(`@Scheduled(fixedDelayString = "${gatewai.dispatch.poll-interval-ms:5000}")`):

1. read the current carbon intensity of every **candidate zone**
   (`CarbonIntensityProvider.gramsCo2PerKwh(zone)`);
2. hand the `zone → intensity` map to the `DispatchDeferredJobsUseCase`.

## Greenest-zone selection & execution

`CarbonAwareZoneSelector.greenest(intensitiesByZone)` returns the zone with the
**minimum** intensity (pure domain logic). `DeferredChatService` then, for each
queued job: marks it `RUNNING`, binds the chosen zone into the
`CarbonZoneContext.CURRENT` Scoped Value, runs the completion through the normal
`ChatCompletionUseCase` (so cache + routing + green accounting all apply), and
records the result/zone or the failure.

Because the zone is bound as a Scoped Value, green accounting picks up the
**zone-specific** grid intensity for that job without any extra parameter passing
(`ChatCompletionService.accountGreen` reads `CarbonZoneContext.CURRENT`).

## What is real vs accounting

- **Temporal shifting is real**: the job genuinely runs later, when/where the
  chosen zone is greenest.
- **Geography is accounting, not physical**: selecting a zone changes the
  *intensity used for accounting*, it does not execute the inference in another
  region. See
  [`carbon-intensity-reliability.md`](carbon-intensity-reliability.md).

## Configuration

`gatewai.dispatch.*`:

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `false` | master switch; off → submit queues but no worker runs |
| `poll-interval-ms` | `5000` | worker tick interval |
| `candidate-zones` | `FR,SE,DE,PL` | zones considered for greenest selection |

Disabled by default: the async endpoint will queue jobs, but nothing executes them
until you enable the worker.
