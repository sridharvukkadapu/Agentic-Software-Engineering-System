package com.schwab.urlshortener.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;

public record CreateUrlRequest(
    @NotBlank
    @Pattern(regexp = "^https?://.+", message = "longUrl must be an absolute http(s) URL")
    String longUrl,
    Instant expiresAt) {
}
