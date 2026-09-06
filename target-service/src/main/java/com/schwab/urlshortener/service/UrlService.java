package com.schwab.urlshortener.service;

import com.schwab.urlshortener.domain.Url;
import com.schwab.urlshortener.exception.UrlExpiredException;
import com.schwab.urlshortener.exception.UrlNotFoundException;
import com.schwab.urlshortener.repository.UrlRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the create/lookup lifecycle for shortened URLs.
 *
 * Every real-time decision (when a URL was created, whether it has expired) is made
 * against the injected {@link Clock}, never {@code Instant.now()} directly, so expiry can
 * be tested deterministically by supplying a fixed clock rather than sleeping past a real
 * TTL or mocking static time.
 */
@Service
public class UrlService {

    private final UrlRepository urlRepository;
    private final ClickRecorder clickRecorder;
    private final Clock clock;

    public UrlService(UrlRepository urlRepository, ClickRecorder clickRecorder, Clock clock) {
        this.urlRepository = urlRepository;
        this.clickRecorder = clickRecorder;
        this.clock = clock;
    }

    @Transactional
    public Url create(String longUrl, Instant expiresAt) {
        Url url = new Url(longUrl, expiresAt);
        url.setCreatedAt(Instant.now(clock));
        return urlRepository.save(url);
    }

    @Transactional(readOnly = true)
    public Url findByShortCode(String shortCode) {
        return urlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new UrlNotFoundException(shortCode));
    }

    /**
     * Resolves a short code for redirect, returning the URL a caller should be sent to.
     * A metadata-only lookup with no redirect implied can use {@link #findByShortCode}
     * instead, which does not apply expiry rules.
     *
     * The resolution is recorded through {@link ClickRecorder} so the click survives even
     * when the surrounding request goes on to fail, then expiry is applied.
     */
    @Transactional
    public Url resolveForRedirect(String shortCode) {
        Url url = findByShortCode(shortCode);
        clickRecorder.recordClick(shortCode);
        if (url.isExpiredAt(Instant.now(clock))) {
            throw new UrlExpiredException(shortCode);
        }
        return url;
    }
}
