-- Click analytics: a per-URL resolution counter. A parallel, functionally equivalent
-- migration lives under db/migration/h2 for tests; keep the two in sync.
--
-- A counter column rather than a click_events table: the analytics this service exposes
-- is a single total per link, so a row per click would cost an unbounded table and a
-- COUNT(*) on every read to answer the one question anyone asks of it. A separate events
-- table becomes the right shape as soon as anything needs per-click attributes (referrer,
-- timestamp, geography); none of that is in scope here, and adding the table now would be
-- a schema with no reader.
ALTER TABLE urls ADD COLUMN click_count BIGINT NOT NULL DEFAULT 0;
