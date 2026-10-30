package com.nodotextil.trazatex.apigateway.infrastructure.ratelimit;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import com.nodotextil.trazatex.apigateway.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cubo por IP de las peticiones a rutas protegidas SIN credencial válida (5 por minuto en la prueba): pasado el límite reciben 429
 * con Retry-After en lugar de 401. No frena a quien trae un token válido, y no cuenta token vencido ni usuario inactivo.
 */
@TestPropertySource(properties = "trazatex.rate-limit.auth-failure.permits=5")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AuthFailureRateLimitTest extends GatewayTestSupport {

    @BeforeEach
    void stubService() {
        ORG_ACCESS.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
    }

    private int callWith(String authorization) {
        var request = anonymous.get().uri("/api/companies");
        if (authorization != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return request.exchange().returnResult(String.class).getStatus().value();
    }

    @Test
    void afterTheLimitInvalidTokensGetA429WithRetryAfterInsteadOfA401() {
        for (int i = 0; i < 5; i++) {
            assertThat(callWith("Bearer token.invalido." + i)).as("fallo %d".formatted(i + 1)).isEqualTo(401);
        }

        anonymous.get().uri("/api/companies").header(HttpHeaders.AUTHORIZATION, "Bearer token.invalido.6").exchange()
                .expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody().jsonPath("$.code").isEqualTo("TOO_MANY_REQUESTS").jsonPath("$.details").isArray().jsonPath("$.timestamp").isNotEmpty();
        assertThat(callWith("Bearer otro.token.malo")).isEqualTo(429);
        assertThat(ORG_ACCESS.findAll(anyRequestedFor(anyUrl()))).as("nada de esto llegó al servicio").isEmpty();
    }

    @Test
    void aMissingTokenAndAnInvalidOneShareTheSameBucket() {
        for (int i = 0; i < 3; i++) {
            assertThat(callWith(null)).isEqualTo(401);
        }
        for (int i = 0; i < 2; i++) {
            assertThat(callWith("Bearer basura.basura.basura")).isEqualTo(401);
        }

        assertThat(callWith(null)).isEqualTo(429);
        assertThat(callWith("Bearer basura.basura.basura")).isEqualTo(429);
    }

    @Test
    void aValidTokenFromTheSameIpIsNotAffectedByTheGarbageComingFromIt() {
        for (int i = 0; i < 20; i++) {
            callWith("Bearer token.invalido." + i);
        }
        assertThat(callWith("Bearer token.invalido.x")).isEqualTo(429);

        // la planta comparte IP: sus operarios, con token bueno, siguen trabajando
        for (int i = 0; i < 10; i++) {
            assertThat(callWith("Bearer " + TestTokens.forUser(UUID.randomUUID(), "OPERATOR", UUID.randomUUID()))).isEqualTo(200);
        }
    }

    @Test
    void anExpiredTokenIsARealSessionNotAnAttackAndDoesNotSpendTheBucket() {
        JWTClaimsSet expired = TestTokens.operatorClaims()
                .issueTime(Date.from(Instant.now().minusSeconds(7200))).expirationTime(Date.from(Instant.now().minusSeconds(3600))).build();
        String token = TestTokens.sign(TestTokens.KEY, expired);
        for (int i = 0; i < 50; i++) {
            assertThat(callWith("Bearer " + token)).as("vencido %d".formatted(i + 1)).isEqualTo(401);
        }

        for (int i = 0; i < 5; i++) {
            assertThat(callWith("Bearer token.invalido." + i)).as("el cubo seguía lleno").isEqualTo(401);
        }
        assertThat(callWith("Bearer token.invalido.6")).isEqualTo(429);
    }

    @Test
    void thePublicRoutesAreNotAffectedByAnExhaustedBucket() {
        for (int i = 0; i < 10; i++) {
            callWith("Bearer token.invalido." + i);
        }

        anonymous.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();
        anonymous.get().uri("/api/invitations/abc123").exchange().expectStatus().isOk();
    }

    @Test
    void aForgedForwardedForDoesNotEvadeTheBucket() {
        for (int i = 0; i < 5; i++) {
            anonymous.get().uri("/api/companies").header("X-Forwarded-For", "10.0.0." + i).exchange().expectStatus().isUnauthorized();
        }

        anonymous.get().uri("/api/companies").header("X-Forwarded-For", "203.0.113.200").exchange().expectStatus().isEqualTo(429);
    }
}
