# Requirement: faster resolution for popular links

Popular short URLs should be faster to resolve without hammering the database on every
request.

Right now every redirect does a database lookup. For links that get a lot of traffic,
that is unnecessary load: the mapping from short code to long URL basically never
changes once it exists.

Make popular links resolve faster.
