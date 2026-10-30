package com.nodotextil.trazatex.apigateway.infrastructure.revocation;

import com.nodotextil.trazatex.apigateway.KafkaTestSupport;
import com.nodotextil.trazatex.apigateway.RevocationEvents;
import com.nodotextil.trazatex.apigateway.RevocationItSupport;
import com.nodotextil.trazatex.apigateway.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * El Gateway arranca antes de que organization-access haya creado {@code orgaccess.user}: no está listo (readiness 503), las rutas
 * autenticadas responden 503 (no 401, no 200), las públicas funcionan, y en cuanto el tópico aparece carga y queda listo.
 */
class UserRevocationStartupIT extends RevocationItSupport {

    static final String TOPIC = KafkaTestSupport.uniqueTopic();   // NO se crea todavía

    @DynamicPropertySource
    static void revocation(DynamicPropertyRegistry registry) {
        registry.add("trazatex.revocation.enabled", () -> "true");
        registry.add("trazatex.revocation.bootstrap-servers", KafkaTestSupport::bootstrap);
        registry.add("trazatex.revocation.topic", () -> TOPIC);
        registry.add("trazatex.revocation.api-timeout", () -> "PT2S");
        registry.add("trazatex.revocation.retry-initial-backoff", () -> "PT0.2S");
        registry.add("trazatex.revocation.retry-max-backoff", () -> "PT1S");
    }

    @Test
    void withoutTheTopicItIsNotReadyAnd503sAuthenticatedRoutesUntilItAppears() throws Exception {
        stubService();
        UUID inactive = UUID.randomUUID();
        UUID other = UUID.randomUUID();

        // 1) sin tópico
        Thread.sleep(1_500);
        assertThat(readinessStatus()).isEqualTo(503);
        anonymous.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        call(tokenOf(other)).expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("AUTH_UNAVAILABLE");
        assertThat(statusOf("token.invalido.xx")).as("un token inválido sigue siendo 401").isEqualTo(401);
        anonymous.get().uri("/api/license").exchange().expectStatus().isUnauthorized();
        anonymous.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();
        assertThat(ORG_ACCESS.getAllServeEvents().stream().filter(e -> e.getRequest().getUrl().equals("/api/license"))).as("nada autenticado llegó al servicio").isEmpty();

        // 2) organization-access crea el tópico y publica
        KafkaTestSupport.createCompactedTopic(TOPIC, 1);
        KafkaTestSupport.publish(TOPIC, inactive, RevocationEvents.userUpdated(inactive, "INACTIVE"));

        awaitReady(Duration.ofSeconds(20));
        assertThat(statusOf(tokenOf(inactive))).isEqualTo(401);
        assertThat(statusOf(tokenOf(other))).isEqualTo(200);
    }
}
