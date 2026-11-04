# Observability (Phase 14)

Status: complete (Phase 14 review: [PHASE-14-REVIEW.md](../reviews/PHASE-14-REVIEW.md)).

Three signals, each answering a different question:

| Signal | Question | Where |
|---|---|---|
| **Logs** | What happened to this request? | stdout; JSON (ECS) in production, for Loki, ELK or Datadog |
| **Metrics** | Is the system healthy, and since when not? | `/actuator/prometheus` on the management port |
| **Traces** | Where did this slow request spend its time? | OTLP to a collector (Tempo, Jaeger, Datadog), when configured |

## 1. Logs

- Every line carries `[requestId,traceId]`. The request ID is the caller's `X-Request-Id` when safe, otherwise a fresh UUID, and is echoed in the response for support tickets. The trace ID is the W3C trace, shared with upstream and downstream services.
- Async work (emails) keeps both, through `AsyncConfig`'s MDC decorator.
- Production writes ECS JSON (`logging.structured.format.console: ecs`), with the MDC as fields.
- Never logged: passwords, tokens, secrets, email bodies; emails appear masked (`a***@example.com`). See [SECURITY.md](../SECURITY.md).

## 2. Metrics

### Exposure

| Where | What |
|---|---|
| Public port (8080) | Nothing of the actuator in production; `/actuator/**` other than health and info is denied everywhere |
| Management port (`MANAGEMENT_PORT`, 8081, prod) | Probes, info, `/actuator/prometheus` |

The management port must not be published by the load balancer; only Prometheus, inside the network, scrapes it. `ManagementPort` makes the security rule check the port a request arrived on, so the scrape stays closed even if the separate port is not configured.

### Meters

Every meter carries `application="ecommerce-backend"`. Tags never hold an ID, an email or a path with IDs (HTTP metrics use the route template).

| Meter | Kind | Source |
|---|---|---|
| `http_server_requests_seconds{method, uri, status, outcome}` | Timer, SLO buckets 50 ms to 2 s | Spring MVC |
| `jvm_*`, `process_*`, `system_*` | Gauges | Micrometer |
| `hikaricp_connections_{active, pending, max}` | Gauges | Connection pool |
| `lettuce_*` | Timers | Redis client |
| `tasks_scheduled_execution_seconds{code_function, outcome}` | Timer | Every `@Scheduled` job (Phase 12) |
| `auth_logins_total{outcome}` | Counter | `success` or the error code (`auth_invalid_credentials`, `auth_email_not_verified`, ...) |
| `rate_limit_rejected_total{rule}`, `rate_limit_store_errors_total` | Counters | `RateLimitFilter` |
| `cache_requests_total{cache, result=hit/miss/error}` | Counter | `JsonCache` |
| `orders_paid_total`, `orders_shipped_total`, `orders_delivered_total`, `orders_cancelled_total{by}` | Counters, after commit | `OrderMetrics` |
| `orders_revenue_minor_total{currency}` | Counter, cents | `OrderMetrics` |
| `emails_sent_total`, `emails_failed_total{final}` | Counters | `EmailOutboxRelay` |
| `emails_outbox_pending`, `emails_outbox_failed`, `emails_outbox_oldest_age_seconds` | Gauges, read per scrape | `EmailOutboxMetrics` |

### Alerts (Prometheus rules to start from)

| Alert | Expression | For |
|---|---|---|
| High error rate | `sum(rate(http_server_requests_seconds_count{outcome="SERVER_ERROR"}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) > 0.02` | 5m |
| Slow API | `1 - sum(rate(http_server_requests_seconds_bucket{le="0.5"}[5m])) / sum(rate(http_server_requests_seconds_count[5m])) > 0.05` | 10m |
| Pool exhausted | `max(hikaricp_connections_pending) > 0` | 2m |
| Emails stuck | `max(emails_outbox_oldest_age_seconds) > 900` | 5m |
| Emails given up | `max(emails_outbox_failed) > 0` | 0m |
| Credential stuffing | `sum(rate(auth_logins_total{outcome="auth_invalid_credentials"}[5m])) > 5` | 10m |
| Rate limit store down | `increase(rate_limit_store_errors_total[5m]) > 0` | 0m |
| Job failing | `increase(tasks_scheduled_execution_seconds_count{outcome="ERROR"}[30m]) > 0` | 0m |
| No paid orders | `sum(increase(orders_paid_total[1h])) == 0` (business hours only) | 1h |

## 3. Traces

- Micrometer Tracing with the OpenTelemetry bridge. Incoming `traceparent` headers continue the caller's trace; HTTP handling, JDBC-free spans for scheduled jobs and outgoing calls are recorded.
- `TRACING_SAMPLING_PROBABILITY` (default 0.1): the share of traces exported. The trace ID is in the logs for every request, sampled or not.
- Spans are exported only when `MANAGEMENT_OTLP_TRACING_ENDPOINT` is set (for example `http://otel-collector:4318/v1/traces`); without it nothing is sent anywhere.

## 4. Configuration

| Variable | Default | Purpose |
|---|---|---|
| `MANAGEMENT_PORT` | `8081` (prod) | Port of probes and metrics |
| `TRACING_SAMPLING_PROBABILITY` | `0.1` | Share of exported traces |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | unset | OTLP collector; unset = no export |

In dev and tests the actuator stays on the application port, so the scrape is closed there; set `MANAGEMENT_SERVER_PORT=8081` to try it locally. Tests run with metrics export and tracing off (Spring Boot's default), except `ObservabilityIT`, which starts the production layout.
