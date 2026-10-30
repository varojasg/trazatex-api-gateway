package com.nodotextil.trazatex.apigateway.infrastructure.security;

import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationFailedException;
import com.nodotextil.trazatex.apigateway.domain.exception.RateLimitExceededException;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.util.Optional;

/** Frena a quien insiste en pedir rutas protegidas sin credencial válida: la implementación vive en el rate limiting. */
public interface AuthFailureThrottle {

    /** @return el 429 que debe salir en lugar del 401 si esa IP ya agotó su cubo de fallos; vacío si el 401 sale tal cual */
    Optional<RateLimitExceededException> onFailure(ServerHttpRequest request, AuthenticationFailedException failure);
}
