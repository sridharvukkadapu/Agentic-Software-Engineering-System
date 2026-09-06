-- Real Postgres migration, run against the real database in dev and prod. A parallel,
-- functionally equivalent migration lives under db/migration/h2 for tests, since H2 (even
-- in PostgreSQL compatibility mode) does not accept `DEFAULT nextval(...)` as a column
-- default or the `OWNED BY` sequence-ownership clause. Both create the same schema; keep
-- them in sync when this one changes.
CREATE SEQUENCE urls_id_seq;

CREATE TABLE urls (
    id BIGINT PRIMARY KEY DEFAULT nextval('urls_id_seq'),
    short_code VARCHAR(16) NOT NULL,
    long_url VARCHAR(2048) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ
);

ALTER SEQUENCE urls_id_seq OWNED BY urls.id;

CREATE UNIQUE INDEX uq_urls_short_code ON urls (short_code);
