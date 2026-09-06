# target-service

URL shortener service. This is the target codebase the agentic orchestration system
operates on, not a demo of the orchestration itself.

## Run locally

```bash
docker-compose up -d          # starts Postgres on localhost:5433
./gradlew bootRun             # starts the app on localhost:8080
```

Postgres is mapped to host port 5433, not 5432, so it doesn't collide with a
locally-installed Postgres that may already be listening on the default port.

## API

### Create a short URL

```bash
curl -X POST http://localhost:8080/api/urls \
  -H "Content-Type: application/json" \
  -d '{"longUrl": "https://example.com/some/very/long/path"}'
```

An optional `expiresAt` (ISO-8601 instant) can be included; a URL created with no
`expiresAt` never expires.

Returns `201 Created`:

```json
{
  "shortCode": "1",
  "shortUrl": "http://localhost:8080/1",
  "longUrl": "https://example.com/some/very/long/path",
  "createdAt": "2026-01-01T00:00:00Z",
  "expiresAt": null
}
```

### Redirect

```bash
curl -i http://localhost:8080/1
```

Returns `302 Found` with a `Location` header pointing at the long URL, `404` if the code
is unknown, or `410 Gone` if the code exists but has expired.

### Look up metadata (no redirect)

```bash
curl http://localhost:8080/api/urls/1
```

Returns the same shape as create, or `404` if the code is unknown. Unlike redirect, this
does not check expiry: an expired code's metadata is still readable, it just cannot be
followed.

### Correlation id

Every request and error response carries an `X-Correlation-Id` header: the value sent on
the request is echoed back, or a fresh one is generated if the request did not send one.
Every error response body also includes it as `correlationId`.

## Tests

```bash
./gradlew test
```

All tests run against an in-memory H2 database (PostgreSQL compatibility mode) with no
external service and no Docker daemon required. `UrlRepositoryIntegrationTest` and
`UrlServiceIntegrationTest` exercise a real Flyway migration, a real repository, and a
real Spring context, just against H2 instead of Postgres; see
`src/main/resources/db/migration/h2/` for the H2-specific migration kept in sync with the
real Postgres one under `src/main/resources/db/migration/postgresql/`.

## Design notes

- Short codes are base62-encodings of the row's generated id, not random strings
  checked for collisions. This makes code generation deterministic and collision-free
  by construction. The id comes from a named Postgres sequence rather than
  `GenerationType.IDENTITY`, so Hibernate assigns it before the insert runs and a
  `@PrePersist` callback on `Url` can encode the short code into that same insert
  statement, instead of inserting a row with a null code and updating it afterward.
- Schema changes go through Flyway migrations (`src/main/resources/db/migration`), not
  `ddl-auto`, so schema history is reviewable. Postgres and H2 need slightly different
  DDL for the same schema (H2 does not accept Postgres's `DEFAULT nextval(...)` column
  default or its `OWNED BY` sequence-ownership clause), so each vendor gets its own
  migration folder; Flyway's `locations` property picks the right one per profile.
- `Clock` is injected everywhere real-time matters (`ClockConfig`), never
  `Instant.now()` directly, so expiry is testable by supplying a fixed or settable clock
  rather than sleeping past a real TTL.
- `CorrelationIdFilter` puts the correlation id in MDC for the life of a request and
  clears it afterward, so it is available to any log statement without threading it
  through every method signature, and cannot leak onto a later request when the servlet
  container reuses the handling thread.
- Click analytics: every redirect resolution increments a per-URL `click_count`, exposed
  on the lookup endpoint's response. Recorded through `ClickRecorder` in its own
  `REQUIRES_NEW` transaction so a click is not lost when the surrounding request fails.
- Scope for this pass is the core APIs (create, redirect, lookup) plus expiry, clock
  injection, correlation ids, click analytics, and a global exception handler. Rate
  limiting, SSRF validation, idempotency keys, and OpenAPI documentation are deliberately
  deferred to a later change.

> **Note for reviewers:** this service intentionally contains one seeded defect, planted
> in its own clearly labelled commit (`Seed the brownfield regression: expired links still
> count as clicks`) so the `brownfield` scenario has a real bug in real code to find. It
> is described in `scenarios/brownfield/requirement.md` as a bug report, without naming
> the cause. Do not treat it as an oversight.
