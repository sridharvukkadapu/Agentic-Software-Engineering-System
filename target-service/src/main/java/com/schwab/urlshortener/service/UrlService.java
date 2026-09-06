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
    private final Clock clock;

    public UrlService(UrlRepository urlRepository, Clock clock) {
        this.urlRepository = urlRepository;
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
     * Resolves a short code for redirect, checking expiry before returning: an expired
     * code must never be treated as a live redirect target, so this is the one method a
     * controller that redirects should call, not {@link #findByShortCode}, which a
     * metadata-only lookup (no redirect implied) can still use for an expired code.
     *
     * Ordering matters and is load bearing: expiry is checked <em>before</em> the click is
     * recorded. A click counts a visitor who was actually sent somewhere, so a resolution
     * that ends in {@link UrlExpiredException} must not increment anything. Recording
     * first and validating afterward would inflate every expired link's analytics with
     * traffic that never got a redirect, which is exactly the defect
     * {@code scenarios/brownfield} reports.
     */
    @Transactional
    public Url resolveForRedirect(String shortCode) {
        Url url = findByShortCode(shortCode);
        if (url.isExpiredAt(Instant.now(clock))) {
            throw new UrlExpiredException(shortCode);
        }
        url.recordClick();
        return url;
    }
}
