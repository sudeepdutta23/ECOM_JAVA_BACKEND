# Phase 14 review: Observability

- Date: 2026-11-04
- Scope: Prometheus metrics, business and security meters, management port, tracing, log correlation
- Result: complete (see [TESTING.md](../TESTING.md))

## 1. Security review

| Risk | Handling | Evidence |
|---|---|---|
| Metrics readable from the internet (internal hostnames, traffic, business volume) | Scrape only on the management port, which is never published; denied on the public port | `ObservabilityIT.theScrapeIsNotReachableOnThePublicPort` |
| Other actuator endpoints (env, heapdump, beans) leaking secrets | Not exposed, and `/actuator/**` denied apart from health and info | `ObservabilityIT.probesAnswerOnTheManagementPortAndNothingElseIsExposed` |
| Personal data in labels | Tags are error codes, rule names, currencies, cancellers; never IDs or emails | `ObservabilityIT` (no email in the scrape), `OrderMetricsTest` |
| Traces sent somewhere unintended | No exporter without an explicit endpoint | Configuration |
| Health details revealing infrastructure | `show-details: never` | Phase 0 configuration |

## 2. Correctness and cost review

| Risk | Handling |
|---|---|
| Rolled-back payments counted | Order counters listen after commit |
| Label cardinality explosion | Route templates, not raw paths; fixed tag values; SLO buckets instead of full histograms |
| A scrape failing while the database is down | Outbox gauges return NaN instead of throwing |
| Scrape cost | Three small queries on `email_outbox` per scrape, served by its partial index |
| Tracing overhead | Sampling at 10 %; trace IDs are generated for every request regardless |

No defect was found.

### Accepted risks and next steps

| Item | Decision |
|---|---|
| No dashboards in the repository | Built in Grafana from the meters above; the alert table in [OBSERVABILITY.md](../modules/OBSERVABILITY.md) is the starting point |
| Database spans (JDBC) not traced | Add `datasource-micrometer` if slow-query hunting needs it; `QueryPlanIT` covers the known queries |
| The management port is unauthenticated | Network isolation is the control; a scrape token or mTLS if the network cannot be trusted |
