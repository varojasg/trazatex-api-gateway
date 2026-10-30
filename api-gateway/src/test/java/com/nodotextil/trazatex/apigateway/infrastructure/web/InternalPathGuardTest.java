package com.nodotextil.trazatex.apigateway.infrastructure.web;

import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import com.nodotextil.trazatex.apigateway.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.net.URI;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code /internal/**} no existe para el exterior (404 como cualquier ruta inexistente) y las rutas con trucos de normalización
 * no llegan al servicio. El caso difícil: aquí el Gateway tiene un enrutamiento <b>trampa</b> {@code /**} hacia el servicio, y
 * aun así nada de {@code /internal} pasa; no depende de que ninguna ruta lo cubra.
 */
@Import(InternalPathGuardTest.CatchAllRoute.class)
class InternalPathGuardTest extends GatewayTestSupport {

    @TestConfiguration
    static class CatchAllRoute {
        @Bean
        RouteLocator catchAll(RouteLocatorBuilder builder, @Value("${trazatex.gateway.services.organization-access-url}") String url) {
            return builder.routes().route("catch-all-trampa", r -> r.path("/**").uri(url)).build();
        }
    }

    @Value("${local.server.port}")
    int port;

    @BeforeEach
    void stubService() {
        ORG_ACCESS.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
    }

    private int statusOf(HttpMethod method, String rawPath, String token) {
        var request = anonymous.method(method).uri(URI.create("http://localhost:" + port + rawPath));
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return request.exchange().returnResult(String.class).getStatus().value();
    }

    private void assertNotForwarded() {
        assertThat(ORG_ACCESS.findAll(anyRequestedFor(anyUrl()))).as("no debe llegar al servicio").isEmpty();
    }

    @Test
    void laRutaTrampaSiFuncionaParaUnaRutaNormal() {
        assertThat(statusOf(HttpMethod.GET, "/api/anything", TestTokens.operator())).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"})
    void internalDa404ConCualquierMetodoConYSinToken(String method) {
        for (String token : new String[]{null, TestTokens.operator(), TestTokens.forUser(java.util.UUID.randomUUID(), "LICENSE_OWNER", null)}) {
            assertThat(statusOf(HttpMethod.valueOf(method), "/internal/batches/b1/phase-transitions", token)).isEqualTo(404);
        }
        assertNotForwarded();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal", "/internal/", "/internal/x", "/INTERNAL/x", "/Internal/batches/1", "/%69nternal/x", "/%49NTERNAL/x"})
    void internalEnTodasSusFormasDa404(String path) {
        assertThat(statusOf(HttpMethod.POST, path, TestTokens.operator())).isEqualTo(404);
        assertNotForwarded();
    }

    @Test
    void internalDaElMismoCuerpoQueUnaRutaInexistente() {
        anonymous.post().uri("/internal/batches/b1/phase-transitions").exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("ROUTE_NOT_FOUND").jsonPath("$.message").isNotEmpty();
    }

    @Test
    void unaRutaQueSoloContieneInternalMasAdentroNoSeBloquea() {
        assertThat(statusOf(HttpMethod.GET, "/api/internal-notes", TestTokens.operator())).isEqualTo(200);
        assertThat(statusOf(HttpMethod.GET, "/api/internal", TestTokens.operator())).isEqualTo(200);
    }

    @ParameterizedTest
    @MethodSource("tricks")
    void lasRutasConTruquitosDeNormalizacionSeRechazanAntesDeLlegarAlServicio(String path) {
        assertThat(statusOf(HttpMethod.POST, path, TestTokens.operator())).as(path).isEqualTo(400);
        assertNotForwarded();
    }

    static Stream<String> tricks() {
        return Stream.of(
                "/api/companies/../../internal/batches/b1/phase-transitions",
                "/api/auth/login/../../internal/x",
                "/api/%2e%2e/internal/x", "/api/%2E%2E/internal/x", "/api/.%2e/internal/x", "/api/%2e./internal/x",
                "/api/./companies", "/api/companies/.", "/api/companies/..",
                "/api/companies/..%2finternal/x", "/api/companies%2f..%2f..%2finternal/x",
                "/api/companies/..%5cinternal/x", "/api/companies%5C..%5Cinternal/x",
                "/internal;x=1/batches", "/api;jsessionid=1/companies", "/api/companies/..;/internal/x", "/api/companies%3Bx/1",
                "//internal/x", "/api//companies", "/api/companies//",
                "/api/companies%00");
    }

    @Test
    void laQueryStringNoSeToca() {
        assertThat(statusOf(HttpMethod.GET, "/api/companies?next=../internal&a=%2e%2e;b=//", TestTokens.operator())).isEqualTo(200);
    }
}
