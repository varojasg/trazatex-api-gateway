package com.nodotextil.trazatex.apigateway.infrastructure.web;

import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;

/** Servicio lento: pasado el tope, el frontend recibe un {@code ApiError} 504 del Gateway. */
@TestPropertySource(properties = "spring.cloud.gateway.server.webflux.httpclient.response-timeout=500ms")
class GatewayUpstreamFailureTest extends GatewayTestSupport {

    @Test
    void unServicioLentoDa504() {
        ORG_ACCESS.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withFixedDelay(3000)));

        client.get().uri("/api/license").exchange().expectStatus().isEqualTo(504)
                .expectBody().jsonPath("$.code").isEqualTo("GATEWAY_TIMEOUT").jsonPath("$.details").isEmpty();
    }
}
