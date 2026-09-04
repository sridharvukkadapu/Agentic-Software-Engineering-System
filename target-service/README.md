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

Returns `201 Created`:

```json
{
  "shortCode": "1",
  "shortUrl": "http://localhost:8080/1",
  "longUrl": "https://example.com/some/very/long/path",
  "createdAt": "2026-01-01T00:00:00Z"
}
```

### Redirect

```bash
curl -i http://localhost:8080/1
```

Returns `302 Found` with a `Location` header pointing at the long URL.

### Look up metadata (no redirect)

```bash
curl http://localhost:8080/api/urls/1
```

Returns the same shape as create, or `404` if the code is unknown.

## Tests

```bash
./gradlew test
```

Unit tests cover the base62 short code encoder and the web layer (`@WebMvcTest`).
`UrlRepositoryIntegrationTest` and `UrlServiceIntegrationTest` exercise the Flyway
migration, repository, and service against a real Postgres container via Testcontainers,
so they require a running Docker daemon.

If Testcontainers fails with "Could not find a valid Docker environment" on macOS with
Docker Desktop, its socket auto-detection can fail to resolve the Desktop-managed unix
socket. Point it at the real socket explicitly:

```bash
export DOCKER_HOST=$(docker context inspect desktop-linux --format '{{.Endpoints.docker.Host}}')
./gradlew test
```

The build forwards `DOCKER_HOST` from the environment into the test JVM (see
`build.gradle.kts`), since Gradle's `Test` task does not inherit it automatically.

## Design notes

- Short codes are base62-encodings of the row's generated id, not random strings
  checked for collisions. This makes code generation deterministic and collision-free
  by construction. The id comes from a named Postgres sequence rather than
  `GenerationType.IDENTITY`, so Hibernate assigns it before the insert runs and a
  `@PrePersist` callback on `Url` can encode the short code into that same insert
  statement, instead of inserting a row with a null code and updating it afterward.
- Schema changes go through Flyway migrations (`src/main/resources/db/migration`), not
  `ddl-auto`, so schema history is reviewable.
- Scope for this pass is the core APIs only: create, redirect, lookup. Analytics and
  reliability features (rate limiting, caching, Mongo-backed click tracking) are
  deliberately deferred to a later change.
