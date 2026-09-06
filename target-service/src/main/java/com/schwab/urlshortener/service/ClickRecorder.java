package com.schwab.urlshortener.service;

import com.schwab.urlshortener.repository.UrlRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records link resolutions for analytics, in its own transaction.
 *
 * {@code REQUIRES_NEW} so a click is never lost when the surrounding request fails: the
 * count commits independently of whatever the caller's transaction goes on to do. Traffic
 * is real traffic whether or not the request that carried it ended in an error, and
 * analytics that silently drop every errored request under-report demand.
 *
 * A separate bean rather than a method on {@link UrlService}: Spring's transaction
 * proxying does not apply to self-invocation, so a {@code REQUIRES_NEW} method called
 * from another method of the same bean would quietly run in the caller's transaction and
 * do nothing of what it says.
 */
@Component
public class ClickRecorder {

    private final UrlRepository urlRepository;

    public ClickRecorder(UrlRepository urlRepository) {
        this.urlRepository = urlRepository;
    }

    /**
     * Counts one resolution of {@code shortCode}. Unknown codes are ignored rather than
     * raising: a request for a code that does not exist is a 404 for the caller, not an
     * analytics failure, and this must never change the status a caller sees.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordClick(String shortCode) {
        urlRepository.findByShortCode(shortCode).ifPresent(url -> {
            url.recordClick();
            urlRepository.save(url);
        });
    }
}
