package com.nodotextil.trazatex.apigateway.domain.model;

import java.util.Objects;

public record RateLimitPolicy(RateLimit login, RateLimit invitation, RateLimit user, RateLimit company, RateLimit authFailure) {

    public RateLimitPolicy {
        Objects.requireNonNull(login, "login");
        Objects.requireNonNull(invitation, "invitation");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(company, "company");
        Objects.requireNonNull(authFailure, "authFailure");
    }
}
