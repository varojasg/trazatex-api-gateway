package com.nodotextil.trazatex.apigateway.infrastructure.ratelimit;

import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Modo Azure: el cubo de fallos es por cliente real (el valor que agrega el ingress), no por la IP del ingress. */
@TestPropertySource(properties = {"trazatex.rate-limit.auth-failure.permits=3", "trazatex.rate-limit.trust-forwarded-for=true"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class AuthFailureTrustedForwardedForTest extends GatewayTestSupport {

    private int status(String forwardedFor) {
        return anonymous.get().uri("/api/companies").header("X-Forwarded-For", forwardedFor).exchange().returnResult(String.class).getStatus().value();
    }

    @Test
    void eachRealClientBehindTheIngressHasItsOwnFailureBucket() {
        for (int i = 0; i < 3; i++) {
            assertThat(status("6.6.6." + i + ", 198.51.100.1")).isEqualTo(401);
        }

        assertThat(status("198.51.100.1")).as("agotó el suyo (lo que puso a la izquierda no cuenta)").isEqualTo(429);
        assertThat(status("198.51.100.2")).as("otro cliente detrás del mismo ingress").isEqualTo(401);
    }
}
