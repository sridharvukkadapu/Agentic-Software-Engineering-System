package com.schwab.urlshortener.url;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises {@link UrlService#create} through the full Spring context against a real
 * Postgres container. This is the path that actually assigns a short code via the
 * sequence-backed id and the entity's {@code @PrePersist} callback; a repository-only
 * test that sets the short code manually would never catch a regression here.
 */
@Testcontainers
@SpringBootTest
class UrlServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private UrlService urlService;

    @Test
    void createAssignsANonNullShortCode() {
        Url url = urlService.create("https://example.com/created/through/service");

        assertThat(url.getShortCode()).isNotNull().isNotBlank();
        assertThat(url.getLongUrl()).isEqualTo("https://example.com/created/through/service");
    }

    @Test
    void createdUrlCanBeFoundByItsShortCode() {
        Url created = urlService.create("https://example.com/find/me");

        Url found = urlService.findByShortCode(created.getShortCode());

        assertThat(found.getLongUrl()).isEqualTo("https://example.com/find/me");
    }

    @Test
    void successiveCreatesProduceDistinctShortCodes() {
        Url first = urlService.create("https://example.com/first");
        Url second = urlService.create("https://example.com/second");

        assertThat(first.getShortCode()).isNotEqualTo(second.getShortCode());
    }

    @Test
    void findByShortCodeThrowsForUnknownCode() {
        assertThatThrownBy(() -> urlService.findByShortCode("doesNotExist"))
            .isInstanceOf(UrlNotFoundException.class);
    }
}
