# ADR-006: Actuator: health public, Prometheus internal only

- **Status:** Accepted, amended by D-19 and then D-23 (2026-10-11)
- **Date:** 2026-10-11
- **Deciders:** human (+ AI proposal, architect subagent)

## Context NFR-4: "`/actuator/health` on both apps, plus metrics: verifications by result, queue depth or lag, processing duration." D-6: health plus prometheus; prometheus only on the management port inside the compose network. D-13: a server-side `decidedAt − createdAt` histogram is the primary NFR-2 measurement.

## Decision
- Exposure `health,prometheus`.
- Gateway: `management.server.port=8081`, which is `expose`d but not published, plus a health group made available on the public port at `/actuator/health` (additional-path – verify, AM-5). Everything else on 8080 is denyAll → 401.
- Matcher: actuator only, on 8081, not published. Health is checked by compose healthchecks.
- Metrics:
  - `matchgw.verifications.decided{result}` (counter, both apps)
  - `matchgw.matcher.processing` (timer)
  - `matchgw.verification.latency` (timer with percentile histogram, decidedAt − createdAt)
  - `matchgw.queue.depth` (gauge in the gateway, refreshed every 5 s)
- No tenant or subject labels.

## Alternatives considered

| Option | Pros | Cons |
|---|---|---|
| `metrics` endpoint on the public port behind auth | One port | Exposes internals to tenants; not a scrape format |
| Health only | Smallest surface | Fails NFR-4 |
| Per-tenant labels | Richer | Cardinality and privacy (tenant ids in metrics); Could only |

## Consequences
- (+) Metrics are not reachable from outside the compose network.
- (−) Relies on Docker network isolation, with no auth on 8081.
- (−) The health additional-path behaviour must be verified.

## Amendment (D-19, step 1a)

Boot 4.1.1 rejects multi-segment health additional paths (`AdditionalHealthEndpointPath`: "'value' must contain only one segment"),
so `server:/actuator/health` is impossible. Instead, `PublicHealthController` serves GET `/actuator/health` on the public port by
delegating to `HealthEndpoint` and returns the aggregate status only (503 when not UP). Actuator (health + prometheus) stays on the
management port 8081. `ManagementPortGuard` fails startup if `management.server.port` is unset or equal to `server.port`, because the
`EndpointRequest` permit in `SecurityConfig` is only port-scoped while the management server has its own port (security review 1a).

## Amendment 2 (D-23, supersedes the D-19 amendment)

The delegating `PublicHealthController` was a re-implementation of a framework feature. The root cause was this ADR's own design:
a separate management port moves *every* actuator endpoint, including health, off the API port. Now the standard actuator runs on
the API port: exposure `health` only, `show-details`/`show-components: never`, liveness/readiness probes enabled, permitted via
`EndpointRequest.to(HealthEndpoint)`; every other `/actuator/**` path falls into the `denyAll` fallback. `PublicHealthController`
and `ManagementPortGuard` are removed. Consequence: port isolation for Prometheus no longer exists on the gateway – step 10 decides
how gateway metrics are exposed (the matcher's metrics stay on its unpublished port 8081).

## Amendment 3 (D-27, step 10)

All metrics are served by the matcher only, on its unpublished server port 8081 (actuator exposure `health,prometheus`); the
gateway exposes health only (Amendment 2 unchanged, AC-52). Placement of the metrics listed under "Decision":
- `matchgw.verifications.decided{result}` – matcher only, incremented after commit for outcomes it wrote (MATCH, NO_MATCH,
  FAILED incl. reaped MAX_ATTEMPTS). The gateway's FAILED/SUBJECT_ERASED transitions are **not** counted (AM-19 superseded).
- `matchgw.matcher.processing` – one sample per processed claimed job (decided, stale write or released after an error).
- `matchgw.verification.latency` – percentile histogram, one sample per written outcome: `decided_at − created_at` returned
  by the guarded write (DB clock; `decided_at` is the matcher transaction start, so a lower bound of time-to-visible).
- `matchgw.queue.depth` – in the matcher, `count(*)` of PENDING rows refreshed at a fixed rate of 5 s (fixed rate, not fixed
  delay: fixed-delay tasks share the poll thread and would freeze during a long drain). Every replica reports the same global
  value – aggregate with `max`, not `sum`.
