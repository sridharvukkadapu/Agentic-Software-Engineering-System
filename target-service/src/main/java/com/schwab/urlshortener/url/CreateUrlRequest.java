package com.schwab.urlshortener.url;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateUrlRequest(
    @NotBlank
    @Pattern(regexp = "^https?://.+", message = "longUrl must be an absolute http(s) URL")
    String longUrl) {
}
