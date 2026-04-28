# Production Readiness Analysis

This document describes the gap between the prototype and a production
deployment that can credibly meet 99.95% availability with millions of
documents and 1k+ QPS.

## 1. Scalability — handling 100× growth

| Dimension      | Current (prototype)          | Production target                                                                                                                                                       |
| -------------- | ---------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **API tier**   | 1 pod, 1 instance            | 6–20 pods behind ALB / Envoy, autoscaled on CPU + p95 latency. Stateless, so HPA scales horizontally without coordination.                                              |
| **OpenSearch** | single node, 3 shards × 1    | Dedicated 3-master + 6+ data-node cluster. Hot-warm-cold tiers (NVMe → SSD → S3 snapshot). Per-tenant index sharding (large tenants → 12 shards, small → 1). ILM rollover. |
| **Postgres**   | single docker container      | RDS Aurora / CloudSQL with 1 writer + 2+ read replicas. Connection pool via PgBouncer. Read replicas serve `GET /documents/{id}`.                                       |
| **Redis**      | single instance              | Redis Cluster (3 shards × 1 replica) or ElastiCache. Cache + rate limiter counters partitioned by tenant id key.                                                       |
| **Indexing**   | synchronous in the request   | Kafka topic `document.upserts` (12+ partitions, partitioned by tenant) → indexer pool. Backpressure via consumer lag SLO, DLQ for poison messages.                      |
| **Routing**    | round-robin                  | Envoy mesh; sticky-by-tenant routing if/when we add per-tenant in-process caches.                                                                                      |

**Capacity sketch for 1B documents:**
- 1B docs × ~2 KB indexed = ~2 TB raw → ~3 TB on disk after analyzers + replicas at RF=2 → ~6 TB → 12× `i3.2xlarge` data nodes (~5 TB NVMe each, leaves headroom).
- 10k QPS at p95 < 500 ms ≈ ~30 active queries × ~150 ms typical = ~5 cores, so ~12 query-side cores after HA. The bottleneck moves from CPU to fan-out latency: keep tenant indices small (≤ 50 GB primary shards), use `search.preference=_local_shards_replica_prefer` to limit cross-AZ hops.

## 2. Resilience

* **Circuit breakers.** Wrap every OpenSearch + Postgres + Redis call in a
  Resilience4j circuit breaker. Trip on `>50%` failure over 30 s, half-open
  after 10 s. Search degrades to a "search temporarily unavailable" 503,
  document reads degrade to cache-only.
* **Retries.** Exponential backoff with jitter (50 ms → 800 ms, 3 attempts).
  Only retry idempotent operations (GET, DELETE). Indexer retries up to 5×
  before publishing to DLQ.
* **Bulkheads.** Separate thread pools for write vs read traffic so a slow
  indexing path can't starve search.
* **Failover.**
  - OpenSearch: cross-AZ replicas (`number_of_replicas: 2`); cluster
    survives loss of any one AZ.
  - Postgres: Aurora multi-AZ failover (~30 s RTO).
  - Redis: AOF + replicas; rate-limit fail-open if Redis is unavailable
    (degrade gracefully, log + alert) — decided trade-off: do not block all
    traffic on Redis being healthy.
* **Graceful shutdown.** `server.shutdown: graceful` + 30 s drain so
  in-flight requests finish before pod terminates during deploy/HPA.
* **Idempotency.** Indexing keyed by document id; replays from Kafka are
  safe. `POST /documents` should accept an idempotency key header in v2.

## 3. Security

* **AuthN.** OAuth 2 / OIDC. JWT with `tid` (tenant id) claim, validated at
  the gateway. The application trusts only the gateway-injected
  `X-Tenant-ID` (mTLS between gateway and pods).
* **AuthZ.** Per-tenant scopes (`docs:read`, `docs:write`, `docs:admin`).
  Enforce at the controller layer with method security
  (`@PreAuthorize("hasAuthority('docs:write')")`).
* **Tenant isolation.** Defence in depth:
  1. Gateway sets `X-Tenant-ID` from JWT, strips any client-supplied value.
  2. JPA queries always filter by `tenantId` (enforced by service layer +
     a Hibernate filter with `@FilterDef`).
  3. OpenSearch queries are scoped to the tenant index, never `*`.
  4. Periodic offline scan: SELECT a sample of rows and verify
     `tenant_id` matches the index they live in.
* **Encryption.**
  - In transit: TLS 1.3 at the gateway, mTLS east-west, OpenSearch +
    Postgres + Redis on TLS.
  - At rest: EBS / disk encryption (KMS), Postgres TDE, OpenSearch
    encryption-at-rest plugin enabled.
* **Secrets.** Never in env files. AWS Secrets Manager / Vault, mounted via
  CSI driver, rotated on a schedule.
* **API security.** WAF rules for common OWASP top 10, request size
  limits (`POST /documents` capped at 1 MB content), per-tenant rate
  limit + global IP rate limit.
* **Logging hygiene.** Document content is never logged at INFO. PII
  scrubbing in the logging pipeline.
* **Dependency hygiene.** Renovate/Dependabot weekly, SBOM published per
  build, container images scanned in CI (Trivy / Grype), base images
  pinned by digest.

## 4. Observability

* **Metrics.** Micrometer → Prometheus → Grafana. Key SLIs:
  - `http_server_requests_seconds{uri="/search",status,tenant_id}` — p50/p95/p99 latency
  - `opensearch_query_duration_seconds{index,tenant_id}` — backend latency
  - `cache_hit_ratio{cache="search"}` — should sit > 30%
  - `rate_limit_rejections_total{tenant_id}`
  - `kafka_consumer_lag_seconds{topic="document.upserts"}`
* **Logs.** JSON via Logback, with `tenant_id` + `trace_id` MDC keys.
  Shipped to ELK / Loki. 30-day hot retention, 1-year S3 cold.
* **Tracing.** OpenTelemetry SDK; spans for HTTP, JDBC, OpenSearch,
  Redis. End-to-end trace from gateway to OpenSearch shard. Sampling
  starts at 10% and burns down on cost.
* **Alerting.**
  - p95 search latency > 500 ms for 5 min → page
  - Error rate > 1% for 5 min → page
  - Indexer consumer lag > 60 s → page
  - Cluster yellow > 15 min → page; red → immediate page
* **Dashboards.** Per-tenant view (latency, QPS, rejections, top queries) +
  global SRE view (cluster health, JVM, GC, disk).

## 5. Performance

* **Indexing.**
  - Bulk API; batch up to 5 MB or 100 docs per request.
  - Refresh interval `30s` (search-after-write requires explicit
    `refresh=wait_for` — apply on the API write path only when a tenant
    enables that capability).
  - Force-merge to 1 segment for read-heavy historical indices.
* **Search.**
  - Avoid wildcard prefix queries; use edge-ngram analyzers if needed.
  - Use `search_type=query_then_fetch` (default) but cap `from + size`
    via deep-pagination ceiling at 10 000; `search_after` for deeper.
  - Pre-warm filter cache after rolling restarts.
* **JVM.** G1GC, `-XX:MaxRAMPercentage=75`, container-aware
  CPU sizing. JFR continuous profiling enabled in non-prod.
* **Postgres.** Index on `(tenant_id, created_at)`; partition the
  `documents` table by `tenant_id` hash once any single tenant exceeds
  ~10 GB.
* **Connection pools.** Hikari `maximum-pool-size = 2 × CPU` per pod;
  Lettuce shared pool for Redis; Apache HC pool sized to OpenSearch
  parallel-query expectation.

## 6. Operations

* **Deploys.** GitOps via ArgoCD. Blue-green at the service level: route
  ≤ 5% canary traffic via gateway weights for 10 min, watch SLI burn
  rate, then promote. Rollbacks are one git revert.
* **Index rollovers.** ILM policies create `docs-{tenant}-000001`,
  rollover at 50 GB or 30 days. Aliases let the application stay
  ignorant of the rollover.
* **Backups.**
  - Postgres: PITR (continuous WAL) + nightly snapshot, 35-day retention.
  - OpenSearch: daily snapshot to S3, 30-day retention. Snapshot restore
    rehearsed quarterly.
  - Cross-region replication for DR; RPO 15 min, RTO 60 min.
* **Schema migrations.** Flyway, expand-and-contract: add column → backfill
  → start writing → switch reads → drop old column. Each step a separate
  deploy.
* **Index migrations.** Use the Reindex API + alias swap. Never mutate a
  live index mapping.
* **Multi-region (future).** Active-passive: write to primary region,
  cross-region snapshot restore for OpenSearch, logical replication for
  Postgres. Active-active is on the roadmap once the consistency cost is
  acceptable.

## 7. SLA — meeting 99.95% availability

99.95% availability = ~22 min/month of downtime. To get there:

| Risk                       | Mitigation                                                                                                          |
| -------------------------- | ------------------------------------------------------------------------------------------------------------------- |
| Single-AZ failure          | Multi-AZ everywhere (3 AZs minimum). API pods + OpenSearch nodes spread by topology constraints.                    |
| Bad deploy                 | Canary + automated rollback on SLI burn-rate alerts. Each deploy gated by `/actuator/health` + smoke tests.         |
| Noisy neighbour tenant     | Per-tenant rate limiting; isolate top-10 tenants on dedicated index nodes; query-time circuit breakers.            |
| OpenSearch cluster red     | Replicas + cross-AZ; degrade search to read-only or to a stale cached set rather than 5xx-everything.               |
| Postgres failover          | Aurora ~30 s; API retries with backoff during the gap; circuit breaker prevents thundering-herd reconnect.         |
| Cache stampede             | `singleflight` (Caffeine) in front of the Redis cache, jittered TTLs, request-coalescing for hot search keys.       |
| Dependency outage (Kafka)  | Indexer service has a local disk buffer; API can fall back to direct write with a flag, capped throughput.          |

The cheap wins (multi-AZ, canary deploy, per-tenant rate limit, circuit
breaker, graceful shutdown) cover ~90% of incident classes that take a
service from 99.95 → 99.5. The rest is incident-response discipline:
runbooks, paging hygiene, blameless postmortems, regular game-days.
