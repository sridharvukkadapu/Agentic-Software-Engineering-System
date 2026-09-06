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
  redirect endpoints. The 404 response body is a plain JSON object
  `{"error": "not found"}`; it does not need to match any other endpoint's error schema
  beyond the status code.
- A fetch failure, a timeout, and an SSRF block are each cached for the same 1-hour TTL as
  a successful preview. Do not retry more aggressively than a successful lookup would; a
  target that is down stays down for longer than one request, and re-fetching on every
  call would defeat the purpose of caching.
- The SSRF check also blocks IPv6 private and reserved ranges: loopback (`::1`), unique
  local (`fc00::/7`), and link-local (`fe80::/10`), in addition to the IPv4 ranges already
  listed. Treat a target resolving to any of these the same as a fetch failure.
- The outbound fetcher does not follow HTTP redirects. A redirect response (3xx) from the
  target is treated the same as any other fetch failure: HTTP 200, `title` and
  `description` both `null`. This is deliberate, not an oversight: following redirects
  would mean re-validating the SSRF check against a URL the server did not originally
  request, which is exactly the kind of check this requirement does not want to get
  wrong by omission.
- The outbound fetch reads at most 1 megabyte of the target page's response body. If the
  page is larger, stop reading at that limit and parse only what was read; a `<title>` or
  meta description past that point is treated as absent (`null`), not an error.

This is new functionality on the URL shortener; no existing preview mechanism exists to
build on.
