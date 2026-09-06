-- H2 equivalent of db/migration/postgresql/V1__create_urls_table.sql, used only by tests
-- (see src/test/resources/application.yml). Keep this schema identical to the Postgres
-- version. The id column has no inline DEFAULT here (unlike the Postgres version): the
-- entity's Hibernate @SequenceGenerator (GenerationType.SEQUENCE) already reads
-- urls_id_seq and assigns the id explicitly before every insert, so the column default is
-- redundant for correctness, and H2 2.x's DDL grammar for a computed column default is
-- version-sensitive enough that dropping it is simpler than chasing the exact accepted
-- syntax.
CREATE SEQUENCE urls_id_seq;

CREATE TABLE urls (
    id BIGINT PRIMARY KEY,
    short_code VARCHAR(16) NOT NULL,
    long_url VARCHAR(2048) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    expires_at TIMESTAMP WITH TIME ZONE
);

CREATE UNIQUE INDEX uq_urls_short_code ON urls (short_code);
