package com.nodotextil.trazatex.apigateway.domain.exception;

import java.time.Duration;

/** Se agotó un cubo: se responde 429 con {@code Retry-After}. */
public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("Límite de peticiones agotado", null, false, false);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    /** Segundos enteros para la cabecera {@code Retry-After}: se redondea hacia arriba y nunca es menos de 1. */
    public long retryAfterSeconds() {
        long seconds = (retryAfter.toMillis() + 999) / 1000;
        return Math.max(1, seconds);
    }
}
