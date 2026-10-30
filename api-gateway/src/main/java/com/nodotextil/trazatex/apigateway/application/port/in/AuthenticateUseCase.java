package com.nodotextil.trazatex.apigateway.application.port.in;

import com.nodotextil.trazatex.apigateway.domain.model.AuthenticatedUser;
import reactor.core.publisher.Mono;

public interface AuthenticateUseCase {

    /**
     * @param authorizationHeader valor de {@code Authorization} (puede ser nulo)
     * @return la identidad; falla con {@code AuthenticationFailedException} (401) o {@code AuthenticationUnavailableException} (503)
     */
    Mono<AuthenticatedUser> authenticate(String authorizationHeader);
}
