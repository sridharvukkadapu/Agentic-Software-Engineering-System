package com.schwab.urlshortener.domain;

import java.time.Instant;

public record UrlResponse(
    String shortCode,
    String shortUrl,
    String longUrl,
    Instant createdAt,
    Instant expiresAt,
    long clickCount) {

    public static UrlResponse from(Url url, String baseUrl) {
        return new UrlResponse(
            url.getShortCode(),
            baseUrl + "/" + url.getShortCode(),
            url.getLongUrl(),
            url.getCreatedAt(),
            url.getExpiresAt(),
            url.getClickCount());
    }
}
