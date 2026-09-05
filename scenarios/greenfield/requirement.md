# Requirement: Link preview endpoint

Add an endpoint to the URL shortener that returns a preview (title and description) for
a given short code, so a client can show what a shortened link points to before
following it.

- `GET /api/v1/urls/{code}/preview` returns the target URL's page title and meta
  description, resolved from the long URL the short code points to.
- The preview must be cached, since fetching the target page on every request would be
  slow and would hammer whatever site the link points to.
- Fetching an external page must not hang the request indefinitely if the target is slow
  or unreachable; apply a timeout and return a sensible partial or empty result rather
  than blocking.
- If the short code does not exist, return 404, consistent with the existing lookup and
  redirect endpoints.

This is new functionality on the URL shortener; no existing preview mechanism exists to
build on.
