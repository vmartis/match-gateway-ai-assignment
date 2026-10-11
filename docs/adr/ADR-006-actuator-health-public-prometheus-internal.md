# ADR-006: Actuator: health public, Prometheus internal only

- **Status:** Proposed
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
