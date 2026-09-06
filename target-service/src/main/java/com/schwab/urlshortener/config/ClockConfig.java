package com.schwab.urlshortener.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A single injectable {@link Clock}, so service code never calls {@code Instant.now()}
 * directly. Expiry logic (a URL created to expire at a given instant, checked against
 * "now" on every resolve) is otherwise untestable without sleeping or mocking static
 * time; a test can instead construct a fixed {@link Clock} and set it past or before a
 * URL's expiry deterministically.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
