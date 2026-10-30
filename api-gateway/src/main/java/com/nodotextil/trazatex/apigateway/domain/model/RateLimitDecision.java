package com.nodotextil.trazatex.apigateway.domain.model;

import java.time.Duration;

/** @param retryAfter cuánto falta para poder reintentar; {@link Duration#ZERO} si se permitió */
public record RateLimitDecision(boolean allowed, Duration retryAfter) {

    public static final RateLimitDecision ALLOWED = new RateLimitDecision(true, Duration.ZERO);

    public static RateLimitDecision rejected(Duration retryAfter) {
        return new RateLimitDecision(false, retryAfter);
    }
}
