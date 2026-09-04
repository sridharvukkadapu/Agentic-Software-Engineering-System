package com.schwab.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the URL shortener target service.
 *
 * This is the codebase the agentic orchestration layer operates on: it starts as a
 * minimal, real, runnable Spring Boot application so brownfield tasks have actual code
 * to reason about and modify, not a stub.
 */
@SpringBootApplication
public class UrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
