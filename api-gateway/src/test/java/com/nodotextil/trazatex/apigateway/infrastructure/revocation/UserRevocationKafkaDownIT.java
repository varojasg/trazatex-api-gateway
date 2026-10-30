package com.nodotextil.trazatex.apigateway.infrastructure.revocation;

import com.nodotextil.trazatex.apigateway.KafkaTestSupport;
import com.nodotextil.trazatex.apigateway.RevocationItSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Kafka inalcanzable al arrancar: el Gateway no se cae ni deja pasar a nadie autenticado; sigue vivo y atiende lo público. */
class UserRevocationKafkaDownIT extends RevocationItSupport {

    @DynamicPropertySource
    static void revocation(DynamicPropertyRegistry registry) {
        int closed = KafkaTestSupport.closedPort();
        registry.add("trazatex.revocation.enabled", () -> "true");
        registry.add("trazatex.revocation.bootstrap-servers", () -> "localhost:" + closed);
        registry.add("trazatex.revocation.api-timeout", () -> "PT1S");
        registry.add("trazatex.revocation.retry-initial-backoff", () -> "PT0.2S");
        registry.add("trazatex.revocation.retry-max-backoff", () -> "PT1S");
    }

    @Test
    void withoutKafkaItStaysAliveButNotReadyAndRefusesAuthenticatedTraffic() throws Exception {
        stubService();
        Thread.sleep(2_500);

        anonymous.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk();
        assertThat(readinessStatus()).isEqualTo(503);
        call(tokenOf(java.util.UUID.randomUUID())).expectStatus().isEqualTo(503).expectBody().jsonPath("$.code").isEqualTo("AUTH_UNAVAILABLE");
    }
}
