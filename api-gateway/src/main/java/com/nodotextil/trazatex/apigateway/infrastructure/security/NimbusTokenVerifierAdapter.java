package com.nodotextil.trazatex.apigateway.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.ExpiredJWTException;
import com.nodotextil.trazatex.apigateway.application.port.out.TokenVerifierPort;
import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationFailedException;
import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationFailedException.Reason;
import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationUnavailableException;
import com.nodotextil.trazatex.apigateway.domain.model.AuthenticatedUser;
import com.nodotextil.trazatex.apigateway.domain.model.Role;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.text.ParseException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

public class NimbusTokenVerifierAdapter implements TokenVerifierPort {

    private static final Logger log = LoggerFactory.getLogger(NimbusTokenVerifierAdapter.class);

    private final NimbusJwtDecoder decoder;

    public NimbusTokenVerifierAdapter(JWKSource<SecurityContext> jwkSource, Duration clockSkew) {
        var processor = new DefaultJWTProcessor<SecurityContext>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        DefaultJWTClaimsVerifier<SecurityContext> claims = new DefaultJWTClaimsVerifier<>(
                new JWTClaimsSet.Builder().build(), Set.of("sub", "role", "iat", "exp"));
        claims.setMaxClockSkew((int) clockSkew.toSeconds());
        processor.setJWTClaimsSetVerifier(claims);
        decoder = new NimbusJwtDecoder(processor);
        decoder.setJwtValidator(new JwtTimestampValidator(clockSkew));
    }

    @Override
    public Mono<AuthenticatedUser> verify(String token) {
        return Mono.fromCallable(() -> toUser(decoder.decode(token)))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorMap(e -> !(e instanceof AuthenticationFailedException || e instanceof AuthenticationUnavailableException), this::translate);
    }

    private Throwable translate(Throwable e) {
        // Spring conserva la causa Nimbus: distinguir token rechazado de JWKS temporalmente indisponible.
        if (e instanceof JwtException && e.getCause() != null) return translate(e.getCause());
        if (e instanceof ExpiredJWTException) {
            return new AuthenticationFailedException(Reason.TOKEN_EXPIRED, e);
        }
        if (e instanceof RateLimitReachedException) {
            // kid desconocido y el JWKS ya se recargó hace menos de minReloadInterval: para el cliente, un token sin clave
            log.debug("JWT con kid desconocido; JWKS no recargado (límite de recarga)");
            return new AuthenticationFailedException(Reason.INVALID_TOKEN, e);
        }
        if (e instanceof KeySourceException) {
            log.warn("No se pudo obtener el JWKS de organization-access: {}", e.getMessage());
            return new AuthenticationUnavailableException("JWKS no disponible", e);
        }
        if (e instanceof ParseException || e instanceof BadJOSEException || e instanceof JOSEException
                || e instanceof IllegalArgumentException || e instanceof JwtException) {
            return new AuthenticationFailedException(Reason.INVALID_TOKEN, e);
        }
        return e;
    }

    private static AuthenticatedUser toUser(Jwt claims) {
        try {
            UUID userId = UUID.fromString(claims.getSubject());
            Role role = Role.valueOf(claims.getClaimAsString("role"));
            String company = claims.getClaimAsString("companyId");
            return new AuthenticatedUser(userId, role, company == null ? null : UUID.fromString(company));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AuthenticationFailedException(Reason.INVALID_TOKEN, e);
        }
    }
}
