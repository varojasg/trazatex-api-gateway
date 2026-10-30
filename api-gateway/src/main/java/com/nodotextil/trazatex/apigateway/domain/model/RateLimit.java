package com.nodotextil.trazatex.apigateway.domain.model;

import java.time.Duration;

/** {@code permits} peticiones por {@code window}. */
public record RateLimit(long permits, Duration window) {

    public RateLimit {
        if (permits < 1) {
            throw new IllegalArgumentException("permits debe ser al menos 1");
        }
        if (window == null || window.compareTo(Duration.ofSeconds(1)) < 0) {
            throw new IllegalArgumentException("window debe ser al menos 1 s");
        }
    }
}
