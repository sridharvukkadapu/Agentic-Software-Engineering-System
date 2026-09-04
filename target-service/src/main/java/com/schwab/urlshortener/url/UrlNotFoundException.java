package com.schwab.urlshortener.url;

public class UrlNotFoundException extends RuntimeException {

    public UrlNotFoundException(String shortCode) {
        super("No URL found for short code: " + shortCode);
    }
}
