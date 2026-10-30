package com.nodotextil.trazatex.apigateway.infrastructure.ratelimit;

import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Despliegue detrás del ingress de Azure ({@code trust-forwarded-for=true}): se usa el valor que agrega el ingress (el de más a
 * la derecha). Lo que el cliente ponga a la izquierda no cambia de cubo; si la cabecera no sirve, vale la dirección de la conexión.
 */
@TestPropertySource(properties = {"trazatex.rate-limit.login.permits=3", "trazatex.rate-limit.trust-forwarded-for=true", "trazatex.rate-limit.trusted-hops=1"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class RateLimitTrustedForwardedForTest extends GatewayTestSupport {

    @BeforeEach
    void stubService() {
        ORG_ACCESS.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
    }

    private int login(String forwardedFor) {
        var request = anonymous.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON);
        if (forwardedFor != null) {
            request = request.header("X-Forwarded-For", forwardedFor);
        }
        return request.bodyValue("{}").exchange().returnResult(String.class).getStatus().value();
    }

    @Test
    void eachClientBehindTheIngressHasItsOwnBucketTakenFromTheValueTheIngressAdded() {
        for (int i = 0; i < 3; i++) {
            assertThat(login("198.51.100.1")).isEqualTo(200);
        }

        assertThat(login("198.51.100.1")).as("el primer cliente agotó el suyo").isEqualTo(429);
        assertThat(login("198.51.100.2")).as("otro cliente detrás del mismo ingress").isEqualTo(200);
    }

    @Test
    void whatTheClientPutsOnTheLeftNeverChangesTheBucket() {
        for (int i = 0; i < 3; i++) {
            assertThat(login("10.0.0." + i + ", 198.51.100.1")).isEqualTo(200);   // el cliente falsifica a la izquierda; el ingress agrega la real
        }

        assertThat(login("6.6.6.6, 198.51.100.1")).isEqualTo(429);
        assertThat(login("1.2.3.4, 5.6.7.8, 198.51.100.1")).isEqualTo(429);
    }

    @Test
    void withoutAUsableHeaderTheConnectionAddressIsUsed() {
        for (int i = 0; i < 3; i++) {
            assertThat(login(null)).isEqualTo(200);
        }

        assertThat(login(null)).isEqualTo(429);
        assertThat(login("evil.example.com")).as("un valor que no es IP cae en el cubo de la conexión").isEqualTo(429);
        assertThat(login("198.51.100.99")).as("otro cliente con cabecera válida").isEqualTo(200);
    }
}
