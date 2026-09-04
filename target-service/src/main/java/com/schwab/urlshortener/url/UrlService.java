package com.schwab.urlshortener.url;

import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the create/lookup lifecycle for shortened URLs.
 */
@Service
public class UrlService {

    private final UrlRepository urlRepository;

    public UrlService(UrlRepository urlRepository) {
        this.urlRepository = urlRepository;
    }

    @Transactional
    public Url create(String longUrl) {
        Url url = new Url(longUrl);
        url.setCreatedAt(Instant.now());
        return urlRepository.save(url);
    }

    @Transactional(readOnly = true)
    public Url findByShortCode(String shortCode) {
        return urlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new UrlNotFoundException(shortCode));
    }
}
