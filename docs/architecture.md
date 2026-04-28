# Architecture — Distributed Document Search Service

## 1. Goals and constraints

| Requirement                       | Target                                                 |
| --------------------------------- | ------------------------------------------------------ |
| Document scale                    | 10M+ across multiple tenants                           |
| p95 search latency                | < 500 ms                                               |
| Concurrent queries                | 1,000+ QPS                                             |
| Tenant isolation                  | Hard data boundary, no cross-tenant leakage            |
| Horizontal scalability            | Stateless API + sharded search backend                 |
| Availability                      | Designed for 99.95%                                    |

## 2. High-level architecture

```
                     ┌──────────────┐
   clients ──HTTPS──▶│  API Gateway │  (TLS, WAF, JWT verify, global rate limit)
                     └──────┬───────┘
                            │
                ┌───────────┴───────────┐
                ▼                       ▼
        ┌──────────────┐        ┌──────────────┐
        │  API pod #1  │ ...    │  API pod #N  │   Spring Boot 3.5 / Java 17
        │  (stateless) │        │  (stateless) │   - Tenant filter
        └──────┬───────┘        └──────┬───────┘   - Per-tenant rate limit (Redis)
               │                       │            - JPA + OpenSearch + Cache
               ├───────────────┬───────┴─────────┬──────────────┐
               ▼               ▼                 ▼              ▼
        ┌────────────┐  ┌────────────┐    ┌────────────┐  ┌──────────┐
        │ OpenSearch │  │   Redis    │    │ PostgreSQL │  │  Kafka   │
        │  (3 nodes) │  │ (cluster)  │    │  (primary  │  │ (indexer │
        │ shards/    │  │ - cache    │    │  + read    │  │  topic;  │
        │ replicas   │  │ - rate-lim │    │  replicas) │  │   prod)  │
        │ per index  │  │ - sessions │    │            │  │          │
        └────────────┘  └────────────┘    └────────────┘  └──────────┘
```

The prototype runs the API + OpenSearch + Redis + Postgres in a single
`docker-compose` stack and indexes synchronously (no Kafka). The dashed line
above is the production target.

## 3. Data flows

### Indexing (`POST /documents`)

```
client ─▶ TenantFilter ─▶ RateLimitFilter ─▶ DocumentController
                                                     │
                                                     ▼
                                           DocumentService.create()
                                                     │
                                ┌────────────────────┼─────────────────┐
                                ▼                    ▼                 ▼
                       Postgres INSERT       OpenSearch INDEX      Cache evict
                     (source of truth)       (refresh=wait_for)    (document:*)
```

In production the OpenSearch write becomes a Kafka publish; a dedicated
indexer service consumes and writes to OpenSearch with retry + DLQ. That
removes the index from the request critical path and lets the API stay
sub-100 ms even under indexing storms.

### Search (`GET /search?q=...`)

```
client ─▶ TenantFilter ─▶ RateLimitFilter ─▶ SearchController
                                                     │
                                                     ▼
                                          SearchService.search()
                                                     │
                                          Redis GET cache key
                                                     │
                                  ┌─────── hit ──────┴───── miss ───────┐
                                  ▼                                     ▼
                          return cached                          OpenSearch query
                                                                       │
                                                                  cache PUT (60s)
                                                                       │
                                                                       ▼
                                                                   response
```

Cache key: `search::tenant:{tid}:{query}:{page}:{size}`. TTL is short (60 s) so
freshness is preserved while still absorbing the duplicated-query bursts that
dominate real search traffic.

## 4. Storage strategy

| Layer       | Store      | Why                                                   |
| ----------- | ---------- | ----------------------------------------------------- |
| Source of   | PostgreSQL | Strong consistency, transactions, easy backup &       |
| truth       |            | rebuild path. Schema kept lean (`documents` table).   |
| Search      | OpenSearch | Lucene under the hood. Inverted index, BM25 scoring,  |
| index       |            | highlighting, fuzziness, aggregations — everything    |
|             |            | you would otherwise reimplement badly on top of a     |
|             |            | relational store.                                     |
| Cache       | Redis      | Sub-ms reads. Used for (a) search-result cache,       |
|             |            | (b) per-tenant rate-limiter counters, (c) future     |
|             |            | session/JWT denylist.                                 |
| Async queue | Kafka      | (Production) Decouples write API from index latency,  |
|             |            | provides durable replay for index rebuilds.           |

**Why OpenSearch over Postgres FTS?** Postgres `tsvector` is fine to ~1M docs
on a single primary, but it doesn't shard, lacks competitive relevance tuning
(no BM25 per field, no per-field analyzers, no native fuzziness), and shares
the OLTP buffer pool. At 10M+ docs and 1 KQPS, you want a purpose-built engine.

## 5. API contract

| Endpoint                     | Auth headers                  | Response                |
| ---------------------------- | ----------------------------- | ----------------------- |
| `POST /documents`            | `X-Tenant-ID`                 | `201` + `DocumentResponse` |
| `GET /documents/{id}`        | `X-Tenant-ID`                 | `200` + `DocumentResponse` |
| `DELETE /documents/{id}`     | `X-Tenant-ID`                 | `204`                   |
| `GET /search?q=&page=&size=` | `X-Tenant-ID`                 | `200` + `SearchResponse`   |
| `GET /actuator/health`       | none                          | `200` + dependency map  |

**Error envelope** (`com.docsearch.exception.ApiError`):

```json
{
  "timestamp": "2026-04-27T15:00:00Z",
  "status":   429,
  "error":    "Too Many Requests",
  "message":  "Rate limit exceeded for tenant tenantId",
  "path":     "/search"
}
```

`429` responses include a `Retry-After` header. Every rate-limited response
also carries `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and
`X-RateLimit-Reset` headers (seconds until window rollover).

OpenAPI / Swagger UI is exposed at `/swagger-ui.html`.

## 6. Multi-tenancy

* **Identification.** All non-public endpoints require the
  `X-Tenant-ID` header (validated by regex `[a-zA-Z0-9_-]{1,64}`). In
  production, the gateway extracts the tenant from a verified JWT and
  re-injects this header — clients can never spoof it.
* **Storage isolation.** Each Postgres row is stamped with `tenant_id`;
  every JPA query filters by `tenantId`. OpenSearch uses one index per
  tenant (`docs-{tenant}`), giving us the option to:
  * apply per-tenant retention,
  * shard hot tenants more aggressively,
  * physically separate noisy/large tenants onto their own cluster later
    without changing application code.
* **Runtime isolation.** A `ThreadLocal` `TenantContext` is set by
  `TenantFilter` and read in every service. Per-tenant rate limits in
  Redis prevent a single noisy tenant from starving the others.

## 7. Caching strategy

| Layer            | Where           | TTL    | Key shape                        |
| ---------------- | --------------- | ------ | -------------------------------- |
| Search result    | Redis (`search`) | 60 s  | `tenant:query:page:size`         |
| Document detail  | Redis (`document`) | 5 m | `tenant:id` (evicted on update/delete) |
| OpenSearch query | OpenSearch built-in (`request_cache`) | shard-managed | term/aggregation cache |
| HTTP / CDN       | Front of gateway (prod) | per `Cache-Control` | by URL + tenant |

## 8. Consistency model and trade-offs

* **Postgres ⇆ OpenSearch:** eventual consistency. Prototype indexes
  synchronously in the same request (`refresh=wait_for`) so you get
  read-your-writes for *search* immediately after a write. Production moves
  this to Kafka — typical end-to-end indexing lag of 1–3 s, traded against
  a 10× improvement in write throughput and isolation.
* **Search cache:** stale-by-design for up to 60 s. Acceptable because
  the inputs that drive search relevance change slowly and the cache
  exists to absorb duplicated reads, not to be the source of truth.
* **Rate limiter:** fixed window using atomic `INCR + EXPIRE` (Lua). Cheap
  and consistent across pods; allows up to 2× the limit at the boundary
  between two windows. Production should switch to a sliding window or
  token bucket (Bucket4j) for smoother shaping.

## 9. Asynchronous operations (production)

| Topic                | Producer            | Consumer                  | Purpose                             |
| -------------------- | ------------------- | ------------------------- | ----------------------------------- |
| `document.upserts`   | Document API        | Indexer service           | Write to OpenSearch with retry      |
| `document.deletes`   | Document API        | Indexer service           | Delete from OpenSearch              |
| `index.rebuilds`     | Ops / cron          | Indexer service           | Rebuild a tenant index from Postgres |
| `audit.events`       | All write paths     | Audit sink                | Compliance / SIEM                   |

Kafka with 12+ partitions per topic, partitioned by `tenant_id` so per-tenant
ordering is preserved.
