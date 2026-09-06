package com.schwab.urlshortener.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.schwab.urlshortener.domain.Url;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/**
 * Exercises the real Flyway migration and repository against a real (in-memory H2,
 * PostgreSQL-compatibility-mode) database, not a mock, so a passing test proves the
 * schema and queries actually work together. Tagged {@code fast}: H2 starts in-process
 * with no external dependency, so this runs in the orchestrator's quick validate loop
 * with no Docker required.
 */
@Tag("fast")
@DataJpaTest
class UrlRepositoryIntegrationTest {

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
