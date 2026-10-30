package com.nodotextil.trazatex.apigateway.infrastructure.ratelimit;

import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/** Con una ventana corta (3 permisos por 2 s) se ve que Retry-After es verdad: pasado ese tiempo, se vuelve a poder. */
@TestPropertySource(properties = {"trazatex.rate-limit.login.permits=3", "trazatex.rate-limit.login.window=PT2S", "test.case=rate-limit-recovery"})
class RateLimitRecoveryTest extends GatewayTestSupport {

    private int login() {
        return anonymous.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .returnResult(String.class).getStatus().value();
    }

    @Test
    void afterTheAnnouncedRetryAfterTheClientCanTryAgain() throws Exception {
        ORG_ACCESS.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
        for (int i = 0; i < 3; i++) {
            assertThat(login()).isEqualTo(200);
        }

        var rejected = anonymous.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isEqualTo(429).returnResult(String.class);
        long retryAfter = Long.parseLong(rejected.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(1L, 2L);

        Thread.sleep(retryAfter * 1000 + 100);

        assertThat(login()).isEqualTo(200);
    }
}
