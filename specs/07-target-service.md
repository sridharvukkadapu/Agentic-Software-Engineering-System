# Spec 07: Target service, the URL shortener

## Context

The codebase the agents operate on. It must be real enough that impact analysis and
brownfield changes are meaningful, and small enough that it does not consume the schedule.

Independent of specs 01-06 and touches a different directory, so it can be built in a
parallel session.

## Scope

Spring Boot 3.x, Java 21, Maven, in `target-service/`.

### Persistence split

- **Postgres** for URL mappings. Spring Data JPA. Durable, transactional, unique constraints.
- **MongoDB** for click events. Spring Data MongoDB. High write volume, flexible shape,
  aggregation for stats.

The split is a deliberate design decision, not decoration: it gives the impact analysis two
data stores with different consistency characteristics to reason about, and it matches the
job description's stack.

### API

| Method | Path | Notes |
|--------|------|-------|
| POST | `/api/v1/urls` | Body: `url`, optional `expiresAt`, optional `customAlias`. Header `Idempotency-Key`. |
| GET | `/{code}` | 302 to target. 410 when expired. 404 when unknown. |
| GET | `/api/v1/urls/{code}` | Metadata, no click increment. |
| GET | `/api/v1/urls/{code}/stats` | Click count, by day, by referrer. |
| DELETE | `/api/v1/urls/{code}` | Soft delete. |
| GET | `/actuator/health` | Liveness and readiness. |

### Behaviour that matters

- **Short code generation**: base62 over a Postgres sequence, not random with retry.
  Collision-free by construction. Document the choice; it is a likely interview question.
- **Idempotency**: same key plus same URL returns the same code. Same key, different URL is
  a 409.
- **Expiry checked before the click is recorded.** An expired resolve must not increment
  analytics. This is the brownfield scenario's bug, so ship it working, then spec 09 will
  introduce the regression deliberately.
- **Custom alias**: reserved-word list, length bounds, uniqueness.
- **URL validation**: http and https only. Reject `file:`, `javascript:`, `data:`. Reject
  private IP ranges and localhost to prevent SSRF via redirect.
- **Rate limiting**: token bucket per API key, applied to create but not resolve. A Spring
  `Filter`, and a good place to show filter-chain knowledge.
- **Correlation id**: filter reads or generates `X-Correlation-Id`, puts it in MDC, and it
  appears in every log line and every error response.
- **Clock injection** everywhere. `Clock` bean, never `Instant.now()` in service code, so
  expiry is testable without sleeping.

### Structure

```
controller/   thin, DTO mapping and status codes only
service/      business logic
repository/   Postgres JPA and Mongo repositories
domain/       entities and value objects
config/       Clock, rate limiter, OpenAPI, Mongo indices
filter/       correlation id, rate limit, API key auth
exception/    typed exceptions and a @RestControllerAdvice
```

### Tests

- Unit tests for services with a fixed `Clock`.
- Integration tests with Testcontainers for Postgres and Mongo.
- A `@Tag("fast")` set that runs without Docker, so the orchestrator's validate loop stays
  quick, and a full set for the real suite.

### Local run

`docker-compose.yml` with Postgres and Mongo. `scripts/dev-up.sh`. Flyway migrations for
Postgres so schema changes are reviewable artifacts the agents can reason about.

## Acceptance criteria

- AC-07-1: `docker compose up` then `mvn test` passes from clean.
- AC-07-2: Creating with the same idempotency key and URL twice returns the same code.
- AC-07-3: Same idempotency key with a different URL returns 409.
- AC-07-4: Resolving an expired code returns 410 and the click count is unchanged.
- AC-07-5: `file:///etc/passwd` and `http://169.254.169.254/` are both rejected at creation.
- AC-07-6: Exceeding the rate limit returns 429 with a `Retry-After` header.
- AC-07-7: `X-Correlation-Id` from the request appears in the error response body.
- AC-07-8: Two concurrent creates never produce the same short code. Assert under load.
- AC-07-9: Stats aggregate correctly across a day boundary with a fixed clock.
- AC-07-10: Fast-tagged tests pass with no Docker running.

## Out of scope

Auth beyond a static API key. Multi-tenancy. A UI.

## Verify

```bash
cd target-service && docker compose up -d && mvn -B test
```
