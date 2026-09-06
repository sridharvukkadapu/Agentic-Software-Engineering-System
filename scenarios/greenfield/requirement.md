# Requirement: Link preview endpoint

Add an endpoint to the URL shortener that returns a preview (title and description) for
a given short code, so a client can show what a shortened link points to before
following it.

- `GET /api/v1/urls/{code}/preview` returns the target URL's page title and meta
  description, resolved from the long URL the short code points to, as JSON:
  `{"code": "...", "targetUrl": "...", "title": "...", "description": "..."}`. `title`
  and `description` are `null` if the target page has no `<title>` or meta description
  element, or if the fetch times out; this is not an error.
- The preview must be cached in an in-process cache (no external cache infrastructure
  such as Redis is available or required for this feature), keyed by short code, with a
  cache TTL of 1 hour. A cache entry is not proactively invalidated if the short code's
  target URL is later changed; it simply expires and is re-fetched after the TTL, since
  a target URL change is expected to be rare and a stale preview for up to an hour is an
  acceptable trade-off against the complexity of active invalidation.
- Fetching an external page must not hang the request indefinitely if the target is slow
  or unreachable; apply a 3 second timeout on the outbound fetch. On timeout or any fetch
  failure, return HTTP 200 with `title` and `description` both `null` (the endpoint
  itself succeeded; only the enrichment did not), rather than a 5xx or 4xx error.
- `title` and `description`, if present, are each truncated server-side to 500 characters
  before being cached or returned.
- The endpoint must not fetch a target URL that resolves to a private, loopback, or
  link-local address (RFC 1918 ranges, `127.0.0.0/8`, `169.254.0.0/16`, and `localhost`),
  to prevent server-side request forgery. A short code whose target resolves to one of
  these is treated the same as a fetch failure: HTTP 200 with `title` and `description`
  both `null`.
- No rate limiting is required for this endpoint in this iteration; it is out of scope.
- If the short code does not exist, return 404, consistent with the existing lookup and
  redirect endpoints.

This is new functionality on the URL shortener; no existing preview mechanism exists to
build on.
