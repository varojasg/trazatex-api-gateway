package com.nodotextil.trazatex.apigateway.infrastructure.web;

import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/** CORS solo en el Gateway: el origen del frontend (TRAZATEX_FRONTEND_URL) puede llamar; cualquier otro, no. */
@TestPropertySource(properties = "TRAZATEX_FRONTEND_URL=https://app.trazatex.test")
class GatewayCorsTest extends GatewayTestSupport {

    private static final String FRONTEND = "https://app.trazatex.test";

    @BeforeEach
    void stubBackend() {
        ORG_ACCESS.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
    }

    @Test
    void elPreflightDelFrontendSeAutorizaSinLlegarAlServicio() {
        client.options().uri("/api/companies")
                .header(HttpHeaders.ORIGIN, FRONTEND)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,idempotency-key")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND)
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, v -> assertThat(v).contains("POST", "PATCH", "PUT"))
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, v -> assertThat(v.toLowerCase()).contains("idempotency-key", "authorization"))
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);

        assertThat(ORG_ACCESS.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void unOrigenAjenoNoPasaElPreflight() {
        client.options().uri("/api/companies")
                .header(HttpHeaders.ORIGIN, "https://malo.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange().expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void laRespuestaRealLlevaElOrigenPermitidoYExponeRetryAfter() {
        client.get().uri("/api/license").header(HttpHeaders.ORIGIN, FRONTEND)
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND)
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, v -> assertThat(v).contains("Retry-After"));
    }
}
