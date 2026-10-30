package com.nodotextil.trazatex.apigateway.infrastructure.web;

import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class GatewayRoutingTest extends GatewayTestSupport {

    @BeforeEach
    void stubBackend() {
        ORG_ACCESS.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/auth/login", "/api/license", "/api/companies", "/api/companies/1c2d/status", "/api/users",
            "/api/users/9f/status", "/api/invitations/operators", "/api/invitations/abc123", "/api/invitations/abc123/accept",
            "/api/admin-requests", "/api/admin-requests/5/decision", "/api/privacy-settings"})
    void rutasDeOrganizationAccessLlegamConElMismoPathYMetodo(String path) {
        client.post().uri(path + "?page=2&size=5").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"a\":1}")
                .exchange().expectStatus().isOk().expectBody().json("{\"ok\":true}");

        LoggedRequest received = ORG_ACCESS.findAll(anyRequestedFor(anyUrl())).getFirst();
        assertThat(received.getUrl()).isEqualTo(path + "?page=2&size=5");
        assertThat(received.getMethod().getName()).isEqualTo("POST");
        assertThat(received.getBodyAsString()).isEqualTo("{\"a\":1}");
    }

    @Test
    void metodosPutPatchYDeleteTambienSeEnrutan() {
        client.put().uri("/api/privacy-settings").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();
        client.patch().uri("/api/users/9f/status").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();
        client.delete().uri("/api/companies/1").exchange().expectStatus().isOk();

        assertThat(ORG_ACCESS.findAll(anyRequestedFor(anyUrl())).stream().map(r -> r.getMethod().getName()))
                .containsExactly("PUT", "PATCH", "DELETE");
    }

    @Test
    void laIdempotencyKeyYElAuthorizationLlegaAlServicio() {
        client.post().uri("/api/companies").header("Idempotency-Key", "6f0c5b0e-6e89-4b43-9d1c-0f1a2b3c4d5e")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();

        LoggedRequest received = ORG_ACCESS.findAll(anyRequestedFor(anyUrl())).getFirst();
        assertThat(received.header("Idempotency-Key").firstValue()).isEqualTo("6f0c5b0e-6e89-4b43-9d1c-0f1a2b3c4d5e");
    }

    @Test
    void lasRespuestasDelServicioPasanSinCambiosConSuEstadoYCuerpo() {
        ORG_ACCESS.stubFor(WireMock.post(urlEqualTo("/api/companies")).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json").withHeader("Location", "/api/companies/7")
                .withBody("{\"code\":\"LICENSE_LIMIT_REACHED\",\"message\":\"x\",\"details\":[],\"timestamp\":\"2026-10-20T10:00:00Z\"}")));

        client.post().uri("/api/companies").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange()
                .expectStatus().isEqualTo(409)
                .expectHeader().valueEquals(HttpHeaders.LOCATION, "/api/companies/7")
                .expectBody().jsonPath("$.code").isEqualTo("LICENSE_LIMIT_REACHED");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/quality/batches/1/controls", "/api/notifications", "/api/analytics/summary"})
    void rutasDeServiciosQueAunNoExistenDan404ConApiError(String path) {
        client.get().uri(path).exchange().expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("ROUTE_NOT_FOUND")
                .jsonPath("$.message").isNotEmpty()
                .jsonPath("$.details").isArray()
                .jsonPath("$.timestamp").isNotEmpty();

        assertThat(ORG_ACCESS.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void elJwksNoSeExponePorElGatewayYInternalNoSeEnruta() {
        client.get().uri("/.well-known/jwks.json").exchange().expectStatus().isNotFound();
        client.post().uri("/internal/batches/1/phase-transitions").exchange().expectStatus().isNotFound();

        assertThat(ORG_ACCESS.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void elProbeDeVidaResponde() {
        client.get().uri("/actuator/health/liveness").exchange().expectStatus().isOk().expectBody().jsonPath("$.status").isEqualTo("UP");
    }
}
