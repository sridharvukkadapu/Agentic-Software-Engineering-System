CREATE SEQUENCE urls_id_seq;

CREATE TABLE urls (
    id BIGINT PRIMARY KEY DEFAULT nextval('urls_id_seq'),
    short_code VARCHAR(16) NOT NULL,
    long_url VARCHAR(2048) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER SEQUENCE urls_id_seq OWNED BY urls.id;

CREATE UNIQUE INDEX uq_urls_short_code ON urls (short_code);
