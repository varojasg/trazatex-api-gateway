package com.nodotextil.trazatex.apigateway.application.usecase;

import com.nodotextil.trazatex.apigateway.application.port.in.AuthenticateUseCase;
import com.nodotextil.trazatex.apigateway.application.port.out.RevokedUsersPort;
import com.nodotextil.trazatex.apigateway.application.port.out.TokenVerifierPort;
import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationFailedException;
import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationFailedException.Reason;
import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationUnavailableException;
import com.nodotextil.trazatex.apigateway.domain.model.AuthenticatedUser;
import reactor.core.publisher.Mono;

public class AuthenticateService implements AuthenticateUseCase {

    private static final String BEARER = "Bearer ";
    /** Un JWT de este sistema mide ~400 caracteres; más de esto no se intenta ni parsear. */
    static final int MAX_TOKEN_LENGTH = 8192;

    private final TokenVerifierPort tokenVerifier;
    private final RevokedUsersPort revokedUsers;

    public AuthenticateService(TokenVerifierPort tokenVerifier, RevokedUsersPort revokedUsers) {
        this.tokenVerifier = tokenVerifier;
        this.revokedUsers = revokedUsers;
    }

    @Override
    public Mono<AuthenticatedUser> authenticate(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            return Mono.error(new AuthenticationFailedException(Reason.MISSING_TOKEN));
        }
        if (authorizationHeader.length() <= BEARER.length() || !authorizationHeader.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return Mono.error(new AuthenticationFailedException(Reason.INVALID_TOKEN));
        }
        String token = authorizationHeader.substring(BEARER.length()).trim();
        if (token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            return Mono.error(new AuthenticationFailedException(Reason.INVALID_TOKEN));
        }
        // Primero la validez del token: uno inválido es siempre 401, esté o no cargada la lista de usuarios inactivos
        return tokenVerifier.verify(token).flatMap(this::ensureActive);
    }

    private Mono<AuthenticatedUser> ensureActive(AuthenticatedUser user) {
        if (!revokedUsers.isLoaded()) {
            return Mono.error(new AuthenticationUnavailableException("Lista de usuarios inactivos aún sin cargar"));
        }
        if (revokedUsers.isRevoked(user.userId())) {
            return Mono.error(new AuthenticationFailedException(Reason.USER_INACTIVE));
        }
        return Mono.just(user);
    }
}
