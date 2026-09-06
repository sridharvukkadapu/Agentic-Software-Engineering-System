package com.schwab.urlshortener.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.schwab.urlshortener.domain.Url;
import com.schwab.urlshortener.exception.UrlExpiredException;
import com.schwab.urlshortener.exception.UrlNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Exercises {@link UrlService} through the full Spring context against a real (in-memory
 * H2) database. This is the path that actually assigns a short code via the
 * sequence-backed id and the entity's {@code @PrePersist} callback; a repository-only
 * test that sets the short code manually would never catch a regression here.
 *
 * The {@link Clock} bean is replaced with a real, settable {@link MutableClock} (a thin,
 * genuinely time-telling {@code Clock} whose current instant can be moved forward, not a
 * mock), so expiry can be asserted deterministically: a URL is created with a real, fixed
 * {@code expiresAt}, then the clock is moved past or held before it, proving "expiry
 * checked before the click is recorded" without sleeping past a real TTL.
 */
@Tag("fast")
@SpringBootTest
class UrlServiceIntegrationTest {

    @TestConfiguration
    static class ClockTestConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        }
    }

    @Autowired
    private MutableClock clock;

    @Autowired
    private UrlService urlService;

    @Test
    void createAssignsANonNullShortCode() {
        clock.setInstant(Instant.parse("2026-01-01T00:00:00Z"));

        Url url = urlService.create("https://example.com/created/through/service", null);

        assertThat(url.getShortCode()).isNotNull().isNotBlank();
        assertThat(url.getLongUrl()).isEqualTo("https://example.com/created/through/service");
    }

    @Test
    void createdUrlCanBeFoundByItsShortCode() {
        clock.setInstant(Instant.parse("2026-01-01T00:00:00Z"));

        Url created = urlService.create("https://example.com/find/me", null);
        Url found = urlService.findByShortCode(created.getShortCode());

        assertThat(found.getLongUrl()).isEqualTo("https://example.com/find/me");
    }

    @Test
    void successiveCreatesProduceDistinctShortCodes() {
        clock.setInstant(Instant.parse("2026-01-01T00:00:00Z"));

        Url first = urlService.create("https://example.com/first", null);
        Url second = urlService.create("https://example.com/second", null);

        assertThat(first.getShortCode()).isNotEqualTo(second.getShortCode());
    }

    @Test
    void findByShortCodeThrowsForUnknownCode() {
        assertThatThrownBy(() -> urlService.findByShortCode("doesNotExist"))
            .isInstanceOf(UrlNotFoundException.class);
    }

    @Test
    void resolveForRedirectReturnsTheUrlBeforeItsExpiry() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant expiresAt = Instant.parse("2026-01-02T00:00:00Z");
        clock.setInstant(createdAt);
        Url created = urlService.create("https://example.com/not-yet-expired", expiresAt);

        clock.setInstant(expiresAt.minusSeconds(1));

        Url resolved = urlService.resolveForRedirect(created.getShortCode());

        assertThat(resolved.getLongUrl()).isEqualTo("https://example.com/not-yet-expired");
    }

    @Test
    void resolveForRedirectThrowsAfterExpiry() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant expiresAt = Instant.parse("2026-01-02T00:00:00Z");
        clock.setInstant(createdAt);
        Url created = urlService.create("https://example.com/will-expire", expiresAt);

        clock.setInstant(expiresAt.plusSeconds(1));

        assertThatThrownBy(() -> urlService.resolveForRedirect(created.getShortCode()))
            .isInstanceOf(UrlExpiredException.class);
    }

    @Test
    void resolvingALiveLinkRecordsAClick() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        clock.setInstant(createdAt);
        Url created = urlService.create("https://example.com/live", null);
        assertThat(created.getClickCount()).isZero();

        urlService.resolveForRedirect(created.getShortCode());
        urlService.resolveForRedirect(created.getShortCode());

        assertThat(urlService.findByShortCode(created.getShortCode()).getClickCount()).isEqualTo(2);
    }

    /**
     * The ordering constraint, asserted against the persisted counter rather than against
     * call order: expiry is checked before the click is recorded, so a resolution that
     * fails with {@link UrlExpiredException} leaves the count untouched. Reading the count
     * back from the repository (not from the entity the failed call returned, since it
     * returned nothing) is what makes this a real check on committed state instead of on
     * in-memory bookkeeping.
     */
    @Test
    void expiryIsCheckedBeforeTheClickIsRecordedSoAnExpiredLinkNeverCounts() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant expiresAt = Instant.parse("2026-01-02T00:00:00Z");
        clock.setInstant(createdAt);
        Url created = urlService.create("https://example.com/expires", expiresAt);

        clock.setInstant(expiresAt.minusSeconds(1));
        urlService.resolveForRedirect(created.getShortCode());
        assertThat(urlService.findByShortCode(created.getShortCode()).getClickCount()).isEqualTo(1);

        clock.setInstant(expiresAt.plusSeconds(1));
        assertThatThrownBy(() -> urlService.resolveForRedirect(created.getShortCode()))
            .isInstanceOf(UrlExpiredException.class);

        assertThat(urlService.findByShortCode(created.getShortCode()).getClickCount())
            .as("an expired resolution attempt must not be counted as a click")
            .isEqualTo(1);
    }

    /**
     * A real {@link Clock} whose current instant can be moved, standing in for
     * {@code ClockConfig}'s system clock bean in tests. Not a mock: {@code instant()} and
     * {@code withZone()} behave exactly as any other real {@code Clock} would, just
     * reading from a settable field instead of the system clock, which is what lets it be
     * shared, read, and advanced across multiple calls within one test without the setup
     * ceremony (and fragility) of stubbing a mocked {@code Clock}'s every method.
     */
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;
        private final java.time.ZoneId zone;

        MutableClock(Instant initial) {
            this(initial, ZoneOffset.UTC);
        }

        private MutableClock(Instant initial, java.time.ZoneId zone) {
            this.instant = new AtomicReference<>(initial);
            this.zone = zone;
        }

        void setInstant(Instant instant) {
            this.instant.set(instant);
        }

        @Override
        public java.time.ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return new MutableClock(instant.get(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
