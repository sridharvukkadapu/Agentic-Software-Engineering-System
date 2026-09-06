# Requirement: Health check endpoint

Add a `GET /api/health` endpoint to the URL shortener that returns `{"status": "ok"}`
with HTTP 200 when the service is running and can reach its database.

- The endpoint takes no parameters and requires no authentication.
- It performs a trivial database query (e.g. `SELECT 1`) to confirm the datastore is
  reachable, with a 2 second timeout.
- If the database check succeeds, respond `{"status": "ok"}` with HTTP 200.
- If the database check fails or times out, respond `{"status": "unavailable"}` with
  HTTP 503.
- This endpoint is not rate limited and is safe to call frequently (e.g. by a load
  balancer or orchestrator), since it does no writes.
- No response body field beyond `status` is required.

This is new functionality; no existing health check endpoint exists to build on.
