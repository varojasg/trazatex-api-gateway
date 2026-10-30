package com.nodotextil.trazatex.apigateway.infrastructure.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.URLBasedJWKSetSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import com.nodotextil.trazatex.apigateway.application.port.in.AuthenticateUseCase;
import com.nodotextil.trazatex.apigateway.application.port.out.RevokedUsersPort;
import com.nodotextil.trazatex.apigateway.application.port.out.TokenVerifierPort;
import com.nodotextil.trazatex.apigateway.application.usecase.AuthenticateService;
import com.nodotextil.trazatex.apigateway.infrastructure.security.AuthFailureThrottle;
import com.nodotextil.trazatex.apigateway.infrastructure.security.AuthenticationGatewayFilter;
import com.nodotextil.trazatex.apigateway.infrastructure.security.NimbusTokenVerifierAdapter;
import com.nodotextil.trazatex.apigateway.infrastructure.security.PublicRoutes;
import com.nodotextil.trazatex.apigateway.infrastructure.security.StrictRateLimitedJWKSetSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
class SecurityConfig {

    /** Tope del JWKS descargado: el de organization-access mide ~400 bytes. */
    private static final int JWKS_SIZE_LIMIT_BYTES = 64 * 1024;

    @Bean
    JWKSource<SecurityContext> jwkSource(JwtProperties props) {
        URL url = jwksUrl(props.jwksUri());
        DefaultResourceRetriever retriever = new DefaultResourceRetriever(
                (int) props.connectTimeout().toMillis(), (int) props.readTimeout().toMillis(), JWKS_SIZE_LIMIT_BYTES);
        // descarga limitada (estricto: una cada minReloadInterval, ver StrictRateLimitedJWKSetSource) + caché con vigencia +
        // sigue sirviendo con la caché si organization-access cae (tolerancia = vigencia del JWT)
        JWKSetSource<SecurityContext> limited = new StrictRateLimitedJWKSetSource<>(
                new URLBasedJWKSetSource<>(url, retriever), props.minReloadInterval().toMillis(), props.failureRetryInterval().toMillis());
        return JWKSourceBuilder.<SecurityContext>create(limited)
                .cache(props.cacheTtl().toMillis(), 5_000)
                .refreshAheadCache(false) // sin descargas en segundo plano: solo cuando una petición lo necesita
                .rateLimited(false)
                .outageTolerant(props.outageTolerance().toMillis())
                .build();
    }

    @Bean
    TokenVerifierPort tokenVerifier(JWKSource<SecurityContext> jwkSource, JwtProperties props) {
        return new NimbusTokenVerifierAdapter(jwkSource, props.clockSkew());
    }

    @Bean
    AuthenticateUseCase authenticateUseCase(TokenVerifierPort tokenVerifier, RevokedUsersPort revokedUsers) {
        return new AuthenticateService(tokenVerifier, revokedUsers);
    }

    @Bean
    PublicRoutes publicRoutes() {
        return new PublicRoutes();
    }

    @Bean
    AuthenticationGatewayFilter authenticationGatewayFilter(PublicRoutes publicRoutes, AuthenticateUseCase authenticate,
                                                            AuthFailureThrottle authFailureThrottle) {
        return new AuthenticationGatewayFilter(publicRoutes, authenticate, authFailureThrottle);
    }

    private static URL jwksUrl(String uri) {
        try {
            URI parsed = URI.create(uri);
            if (!"http".equals(parsed.getScheme()) && !"https".equals(parsed.getScheme())) {
                throw new IllegalStateException("JWKS_URI debe ser http(s): " + uri);
            }
            return parsed.toURL();
        } catch (IllegalArgumentException | MalformedURLException e) {
            throw new IllegalStateException("JWKS_URI no es una URL válida: " + uri, e);
        }
    }
}
