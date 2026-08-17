# Carbon-aware deferred dispatch

For non-interactive workloads, a request can be submitted asynchronously and run
later at the **greenest** candidate zone. Sources:
`adapter/in/web/AsyncChatCompletionController`,
`application/service/DeferredChatService`,
`infrastructure/dispatch/{CarbonAwareDispatchWorker, DispatchProperties,
DispatchSchedulingConfig}`,
`infrastructure/persistence/{JpaDeferredJobStore, DeferredJobEntity,
DeferredJobJson}`, and the domain `CarbonAwareZoneSelector`,
`CarbonZoneContext`, `DeferredJob`, `DeferredJobStatus`.

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
COMPLETED | FAILED`. Since **v3 lot B.2** jobs live in the `deferred_job`
table (`JpaDeferredJobStore`), so they **survive a restart** and are **visible to
every replica** — a client can poll the status through a load balancer instead of
hoping to reach the node that took the submission. It was a
`ConcurrentHashMap` before, which is why the two used to be false.

### Claiming, not listing

A worker does not read the queue and then update it — with two workers that is a
race both of them win. It **claims**:

```sql
SELECT id FROM deferred_job
 WHERE status = 'QUEUED' ORDER BY submitted_at
 FOR UPDATE SKIP LOCKED LIMIT 1
```

inside the transaction that flips that row to `RUNNING`. `SKIP LOCKED` is what
makes the second worker step over a row the first is claiming rather than queue
behind it: no job runs twice, and no worker blocks.

**One job per claim, deliberately.** A batch claim would stamp every job's lease
at the moment the batch was taken, so the last job of a slow batch could have its
lease expire before it even started. Taking one at a time makes the lease start
when the work does, and lets two workers interleave on one queue instead of
splitting it into blocks. A tick stops after
`gatewai.dispatch.max-jobs-per-tick` (20) so a full queue cannot keep one node
busy indefinitely; the rest goes to whoever asks first on the next tick.

### The lease, and the honest guarantee

A claim is a **lease** (`claimed_by`, `lease_expires_at`), not a promise: the node
holding it can die mid-job, and then nothing releases the `RUNNING` row. Every
dispatch tick therefore starts by requeueing rows whose lease has expired —
recovery by whichever node is still alive, rather than by the dead one coming
back, which is what a requeue-on-startup rule would have needed.

The trade-off is explicit:

- **Concurrent claims are exactly-once.** Two workers never get the same job.
- **A lease expiry is at-least-once.** A job whose execution genuinely outlives
  `gatewai.dispatch.job-lease-ms` (5 min) is requeued and can run twice. The
  default is sized well above one completion for that reason; raise it if your
  provider is slow, lower it to recover a dead node's jobs sooner.

`chosen_zone` is cleared on requeue: the job will be re-claimed later, and the
greenest zone then is not the one it was picked for.

### What the table holds

`deferred_job` stores the **prompt in clear text** — it has to, since the request
runs long after the client is gone. That makes it the second place prompts are
persisted, after the vector cache; see the compliance note in
[`decision-tracing.md`](decision-tracing.md). There is **no retention policy on
it yet**: completed jobs stay until deleted by hand.

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
**minimum** intensity (pure domain logic). `DeferredChatService` then claims one
job at a time and, for each: binds the chosen zone into the
`CarbonZoneContext.CURRENT` Scoped Value, runs the completion through the normal
`ChatCompletionUseCase` (so cache + routing + green accounting all apply), and
records the result or the failure. It writes no `RUNNING` transition of its own —
the claim was that write, and repeating it from the application layer would be a
second, non-atomic path into the same state.

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
| `max-jobs-per-tick` | `20` | claims per tick, so one node cannot monopolise the queue |
| `job-lease-ms` | `300000` | how long a claim is held before another node may take the job back |

Plus `gatewai.instance-id` (unset): the name written to `claimed_by`. Defaults to
`host:pid`, which is enough to tell replicas apart; set it when the deployment
already has a name for the node.

Disabled by default: the async endpoint will queue jobs, but nothing executes them
until you enable the worker.

## Verified across two nodes (v3 lot B.2)

Two JVMs on one Postgres, `mock` egress:

| Criterion | Result |
|---|---|
| Jobs survive a restart | 2 jobs submitted on node A stayed `QUEUED` with **both nodes stopped** |
| Cross-node completion | node B, started after A had been killed, ran both — `claimed_by = node-b`, lease released |
| Readable from either node | the restarted node A returned the full result for a job node B had run |
| Exactly once, concurrently | 30 more jobs, both nodes dispatching: **32 jobs, 32 executions, 0 duplicates** counted in `request_log` by `correlation_id` (the job id) |
| Load actually spread | claims split 10 / 22 between the nodes — uneven because the ticks are not in phase, which is the point of claiming rather than partitioning |
| Lease recovery | `JpaDeferredJobStoreClaimTest` (integration): a claimed, unfinished job returns to `QUEUED` with its zone cleared and is claimable again |
