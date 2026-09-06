-- H2 equivalent of db/migration/postgresql/V2__add_click_count.sql, used only by tests
-- (see src/test/resources/application.yml). Keep this schema identical to the Postgres
-- version. This statement happens to be valid in both dialects, but it stays duplicated
-- here because Flyway is pointed at exactly one vendor directory per environment: a
-- migration that exists only under postgresql/ would never run against the test database
-- at all, and the schemas would silently drift.
ALTER TABLE urls ADD COLUMN click_count BIGINT NOT NULL DEFAULT 0;
