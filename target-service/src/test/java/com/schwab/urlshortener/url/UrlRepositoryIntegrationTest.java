package com.schwab.urlshortener.url;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;

/**
 * Exercises the Flyway migration and repository against a real Postgres container, not a
 * mock, so a passing test proves the schema and queries actually work together.
 *
 * {@code replace = Replace.NONE} is required: {@code @DataJpaTest} defaults to swapping
 * in an embedded database, which would silently bypass the Testcontainers Postgres
 * wired up below.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UrlRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private UrlRepository urlRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void savesAndFindsUrlByShortCode() {
        Url url = new Url("https://example.com/some/long/path");
        url.setShortCode("abc123");
        url.setCreatedAt(Instant.now());
        entityManager.persistAndFlush(url);

        Optional<Url> found = urlRepository.findByShortCode("abc123");

        assertThat(found).isPresent();
        assertThat(found.get().getLongUrl()).isEqualTo("https://example.com/some/long/path");
    }

    @Test
    void returnsEmptyForUnknownShortCode() {
        Optional<Url> found = urlRepository.findByShortCode("doesNotExist");

        assertThat(found).isEmpty();
    }

    @Test
    void enforcesUniqueShortCodeAtTheDatabaseLevel() {
        Url first = new Url("https://example.com/first");
        first.setShortCode("dup1");
        first.setCreatedAt(Instant.now());
        entityManager.persistAndFlush(first);

        Url second = new Url("https://example.com/second");
        second.setShortCode("dup1");
        second.setCreatedAt(Instant.now());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> entityManager.persistAndFlush(second))
            .isInstanceOf(org.hibernate.exception.ConstraintViolationException.class);
    }
}
