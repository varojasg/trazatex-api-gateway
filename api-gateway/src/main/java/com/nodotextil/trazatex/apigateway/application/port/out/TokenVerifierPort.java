package com.nodotextil.trazatex.apigateway.application.port.out;

import com.nodotextil.trazatex.apigateway.domain.model.AuthenticatedUser;
import reactor.core.publisher.Mono;

public interface TokenVerifierPort {

    /**
     * Verifica firma (RS256, clave por {@code kid}), vigencia y claims.
     *
     * @return la identidad; falla con {@code AuthenticationFailedException} si el token no sirve o con
     *         {@code AuthenticationUnavailableException} si no se pudieron obtener las claves
     */
    Mono<AuthenticatedUser> verify(String token);
}
