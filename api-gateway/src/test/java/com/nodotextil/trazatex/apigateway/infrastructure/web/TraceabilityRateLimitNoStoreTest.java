package com.nodotextil.trazatex.apigateway.infrastructure.web;

import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/** El 429 del límite de peticiones, que genera el Gateway antes de llegar al servicio, también sale con {@code Cache-Control: no-store} en estas rutas. */
@TestPropertySource(properties = "trazatex.rate-limit.user.permits=2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TraceabilityRateLimitNoStoreTest extends GatewayTestSupport {

    @Test
    void theRateLimitedAnswerIsNotCacheable() {
        TRACEABILITY.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
        for (int i = 0; i < 2; i++) {
            assertThat(client.get().uri("/api/traceability/lineages?qrCode=1").exchange().returnResult(String.class).getStatus().value()).isEqualTo(200);
        }

        var limited = client.get().uri("/api/traceability/lineages?qrCode=1").exchange().returnResult(String.class);

        assertThat(limited.getStatus().value()).isEqualTo(429);
        assertThat(limited.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(limited.getResponseHeaders().getOrEmpty(HttpHeaders.CACHE_CONTROL)).containsExactly("no-store");
    }
}
