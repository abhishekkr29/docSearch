# docSearch

A prototype distributed document search service built with **Spring Boot 3.5
(Java 17) + Gradle**, **OpenSearch**, **Redis**, and **PostgreSQL**. It
demonstrates multi-tenancy, per-tenant rate limiting, layered caching, and
a production-grade architecture story.

> Full design write-up lives in `docs/`:
> - [`docs/architecture.md`](docs/architecture.md)
> - [`docs/production-readiness.md`](docs/production-readiness.md)
> - [`docs/experience-showcase.md`](docs/experience-showcase.md)

## TL;DR — run it

```bash
# Builds the API image, starts OpenSearch + Redis + Postgres + API
docker compose up --build -d

# Wait for the stack to come up, then seed it
./scripts/seed.sh

# Search across the seeded tenants
curl -s 'http://localhost:8080/search?q=distributed' -H 'X-Tenant-ID: tenantId' | jq .
curl -s 'http://localhost:8080/search?q=memo'        -H 'X-Tenant-ID: globex' | jq .

# Inspect health
curl -s http://localhost:8080/actuator/health | jq .
```

OpenAPI / Swagger UI: <http://localhost:8080/swagger-ui.html>

## Architecture (one-liner)

```
client ──▶ TenantFilter ──▶ RateLimitFilter ──▶ Controller ──▶ Service
                                                                  │
                                              ┌───────────────────┼──────────────────┐
                                              ▼                   ▼                  ▼
                                          Postgres            OpenSearch           Redis
                                       (source of truth)    (search index)    (cache + RL)
```

- **Tenant isolation:** required `X-Tenant-ID` header → `ThreadLocal` →
  `tenant_id` column in Postgres + dedicated index `docs-{tenant}` in
  OpenSearch.
- **Rate limiting:** Redis-backed fixed window per tenant (Lua INCR/EXPIRE),
  100 req/min default. Returns `429` with `Retry-After` and
  `X-RateLimit-*` headers.
- **Caching:** Spring Cache → Redis. Search results TTL 60 s, document
  detail TTL 5 min (evicted on update/delete).
- **Search:** OpenSearch multi-match across `title^3, content, tags^2,
  author` with fuzziness `AUTO` and per-field highlights.

## API

| Method   | Path                                  | Description                         |
| -------- | ------------------------------------- | ----------------------------------- |
| `POST`   | `/documents`                          | Index a new document (`201`)        |
| `GET`    | `/documents/{id}`                     | Retrieve document details           |
| `DELETE` | `/documents/{id}`                     | Remove a document (`204`)           |
| `GET`    | `/search?q=&page=&size=`              | Full-text search                    |
| `GET`    | `/actuator/health`                    | Health + dependency status          |

All non-actuator endpoints require `X-Tenant-ID: <tenant>`.

### Sample requests

```bash
# Index
curl -sS -X POST http://localhost:8080/documents \
  -H 'Content-Type: application/json' -H 'X-Tenant-ID: tenantId' \
  -d '{
        "title":   "Distributed Document Search Service",
        "content": "A practical guide to building distributed systems at scale.",
        "author":  "ezio",
        "tags":    "distributed,systems"
      }'

# Search (includes highlights + score)
curl -sS 'http://localhost:8080/search?q=distributed&page=0&size=10' \
  -H 'X-Tenant-ID: tenantId' | jq .

# Get / Delete
curl -sS -H 'X-Tenant-ID: tenantId' http://localhost:8080/documents/<id>
curl -sS -X DELETE -H 'X-Tenant-ID: tenantId' http://localhost:8080/documents/<id>
```

A Postman collection is in [`postman/`](postman/) and a curl walk-through
covering CRUD, multi-tenant isolation, validation errors, and the rate-limit
demo lives at [`scripts/api-examples.sh`](scripts/api-examples.sh).

## Local development (without Docker)

You need Java 17 and a running OpenSearch + Redis. Postgres is optional —
the `dev` profile uses an in-memory H2 in PostgreSQL-compat mode.

```bash
./gradlew bootRun                     # default profile = dev (H2 + local OS/Redis)
./gradlew test                        # run tests (23 tests across 5 classes)
./gradlew build                       # full build incl. tests + bootJar
./gradlew bootJar && \
  java -jar build/libs/docsearch.jar  # standalone jar
```

## Tests

`./gradlew test` runs **23 tests across 5 classes**:

| Class                                    | What it covers                                                  |
| ---------------------------------------- | --------------------------------------------------------------- |
| `TenantContextTest`                      | ThreadLocal set/get/require/clear + cross-thread isolation      |
| `RateLimitServiceTest`                   | Lua-script result mapping, disabled mode, per-tenant key shape  |
| `DocumentControllerWebTest` (`@WebMvcTest`) | CRUD happy + 400 validation + 404 + missing/invalid tenant + 429 |
| `SearchControllerWebTest` (`@WebMvcTest`)   | Tenant header gate, rate-limit headers, 429 envelope            |
| `DocsearchApplicationTests`              | Smoke test the entry-point class loads                          |

Slice tests stand up only the controller + filters + advice (`MockMvc`),
so the suite runs in <1 s and needs no Docker. End-to-end coverage of the
real OpenSearch + Redis paths is exercised by `scripts/api-examples.sh`
against the live `docker compose` stack.

## Configuration

Override via env vars (matching `src/main/resources/application.yml`):

| Variable                         | Default       | Notes                          |
| -------------------------------- | ------------- | ------------------------------ |
| `SPRING_PROFILES_ACTIVE`         | `dev`         | use `docker` in compose        |
| `OPENSEARCH_HOST` / `_PORT`      | `localhost:9200` |                              |
| `REDIS_HOST` / `_PORT`           | `localhost:6379` |                              |
| `POSTGRES_HOST/_PORT/_DB/_USER/_PASSWORD` | -    | `docker` profile only          |
| `docsearch.rate-limit.capacity`  | `100`         | tokens per window per tenant   |
| `docsearch.rate-limit.window-seconds` | `60`     |                                |
| `docsearch.cache.search-ttl-seconds`  | `60`     |                                |

## Layout

```
src/main/java/com/docsearch/
├── DocsearchApplication.java        # @SpringBootApplication entry
├── config/                          # @ConfigurationProperties + beans
│   ├── OpenSearchConfig.java
│   ├── RedisCacheConfig.java
│   └── *Properties.java
├── tenancy/                         # X-Tenant-ID filter + ThreadLocal
├── ratelimit/                       # Redis Lua-backed limiter + filter
├── document/                        # JPA entity, repo, service, controller
├── search/                          # OpenSearch indexing + search
├── health/                          # custom HealthIndicator
└── exception/                       # ApiError + GlobalExceptionHandler
```

## What's intentionally not built

This is a prototype with a 3–4 hour budget. The following are described in
[`docs/production-readiness.md`](docs/production-readiness.md) but not
implemented in code:

- AuthN/AuthZ (gateway-injected JWT → tenant id)
- Async indexing through Kafka with DLQ
- Resilience4j circuit breakers
- Hibernate `@Filter` for defence-in-depth tenant scoping
- Distributed tracing (OpenTelemetry)
- Real load test / benchmark numbers
