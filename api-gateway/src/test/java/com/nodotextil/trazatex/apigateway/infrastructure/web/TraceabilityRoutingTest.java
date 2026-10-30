package com.nodotextil.trazatex.apigateway.infrastructure.web;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import com.nodotextil.trazatex.apigateway.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 05c-3: las rutas de {@code /api/traceability/**} llegan a traceability con la identidad que inyecta el Gateway, y toda respuesta que sale por ellas
 * lleva {@code Cache-Control: no-store} (lo que ve cada empresa depende de quien consulta): la del servicio, la que el servicio olvidó poner y la
 * de los errores del propio Gateway.
 */
class TraceabilityRoutingTest extends GatewayTestSupport {

    private static final String BODY = "{\"ok\":true}";

    @Value("${local.server.port}")
    int port;

    @BeforeEach
    void stubTraceability() {
        TRACEABILITY.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(BODY)));
    }

    private static List<String> cacheControl(WebTestClient.ResponseSpec response) {
        return response.returnResult(String.class).getResponseHeaders().getOrEmpty(HttpHeaders.CACHE_CONTROL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/traceability/lineages?qrCode=QR-1&page=0&size=20", "/api/traceability/batches/1/ancestors",
            "/api/traceability/batches/1/descendants", "/api/traceability/batches/1/lineage"})
    void theRoutesReachTraceabilityAsTheyAreWithTheIdentityTheGatewayInjects(String path) {
        client.get().uri(path).exchange().expectStatus().isOk().expectBody().json(BODY);

        LoggedRequest received = TRACEABILITY.findAll(anyRequestedFor(anyUrl())).getFirst();
        assertThat(received.getUrl()).isEqualTo(path);
        assertThat(received.getMethod().getName()).isEqualTo("GET");
        assertThat(received.header("X-User-Id").firstValue()).isEqualTo(TestTokens.USER_ID.toString());
        assertThat(received.header("X-Company-Id").firstValue()).isEqualTo(TestTokens.COMPANY_ID.toString());
        assertThat(received.header("X-User-Role").firstValue()).isEqualTo("OPERATOR");
        assertThat(ORG_ACCESS.findAll(anyRequestedFor(anyUrl()))).isEmpty();
        assertThat(PRODUCTION.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void theIdentityAClientSendsIsDiscardedSoACompanyCannotQueryAsAnother() {
        UUID other = UUID.fromString("99999999-9999-9999-9999-999999999999");

        client.get().uri("/api/traceability/batches/1/lineage").header("X-Company-Id", other.toString()).header("X-User-Role", "LICENSE_OWNER")
                .header("X-User-Id", other.toString()).exchange().expectStatus().isOk();

        LoggedRequest received = TRACEABILITY.findAll(anyRequestedFor(anyUrl())).getFirst();
        assertThat(received.header("X-Company-Id").values()).containsExactly(TestTokens.COMPANY_ID.toString());
        assertThat(received.header("X-User-Role").values()).containsExactly("OPERATOR");
        assertThat(received.header("X-User-Id").values()).containsExactly(TestTokens.USER_ID.toString());
    }

    @Test
    void whenTheServiceForgetsTheHeaderTheGatewayAddsIt() {
        assertThat(cacheControl(client.get().uri("/api/traceability/lineages?qrCode=QR-1").exchange().expectStatus().isOk())).containsExactly("no-store");
    }

    @Test
    void aCacheableAnswerFromTheServiceIsReplacedNotAddedTo() {
        TRACEABILITY.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withHeader("Cache-Control", "public, max-age=300")
                .withHeader("Cache-Control", "max-age=60").withHeader("ETag", "\"abc\"").withBody(BODY)));

        assertThat(cacheControl(client.get().uri("/api/traceability/batches/1/lineage").exchange().expectStatus().isOk())).containsExactly("no-store");
    }

    @Test
    void whenTheServiceAlreadySendsItThereIsStillASingleValue() {
        TRACEABILITY.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withHeader("Cache-Control", "no-store").withBody(BODY)));

        assertThat(cacheControl(client.get().uri("/api/traceability/batches/1/lineage").exchange().expectStatus().isOk())).containsExactly("no-store");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 405, 409, 500, 503})
    void theErrorsOfTheServiceAreNotCacheableEitherAndArriveIntact(int status) {
        String apiError = "{\"code\":\"BATCH_NOT_FOUND\",\"message\":\"El lote no existe.\",\"details\":[],\"timestamp\":\"2026-10-21T10:00:00Z\"}";
        TRACEABILITY.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(apiError)));

        WebTestClient.ResponseSpec response = client.get().uri("/api/traceability/batches/1/ancestors").exchange().expectStatus().isEqualTo(status);

        assertThat(cacheControl(response)).containsExactly("no-store");
        response.expectBody().jsonPath("$.code").isEqualTo("BATCH_NOT_FOUND");
    }

    @Test
    void theGatewaysOwnErrorsAreNotCacheableEither() {
        // sin token y con un token que no es nuestro: 401 del Gateway; el servicio no se entera
        WebTestClient.ResponseSpec noToken = anonymous.get().uri("/api/traceability/batches/1/lineage").exchange().expectStatus().isUnauthorized();
        WebTestClient.ResponseSpec garbage = anonymous.get().uri("/api/traceability/lineages?qrCode=1").header(HttpHeaders.AUTHORIZATION, "Bearer no-es-un-jwt")
                .exchange().expectStatus().isUnauthorized();

        assertThat(cacheControl(noToken)).containsExactly("no-store");
        assertThat(cacheControl(garbage)).containsExactly("no-store");
        noToken.expectBody().jsonPath("$.code").isEqualTo("MISSING_TOKEN");
        assertThat(TRACEABILITY.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void aServiceThatCutsTheConnectionGives502WithTheSameHeader() {
        TRACEABILITY.stubFor(any(anyUrl()).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        WebTestClient.ResponseSpec response = client.get().uri("/api/traceability/batches/1/lineage").exchange();

        assertThat(response.returnResult(String.class).getStatus().is5xxServerError()).isTrue();
        assertThat(cacheControl(client.get().uri("/api/traceability/batches/1/lineage").exchange())).containsExactly("no-store");
    }

    @Test
    void thePathGuardsRejectionsOnTheseRoutesAreNotCacheableEither() {
        for (String path : List.of("/api/traceability//batches", "/api/traceability/../internal/x", "/api/traceability/%2e%2e/x", "/api/traceability/a%2Fb")) {
            // la ruta cruda: con client.get().uri(String) el cliente la normalizaría antes de enviarla
            WebTestClient.ResponseSpec response = client.get().uri(URI.create("http://localhost:" + port + path)).exchange().expectStatus().isBadRequest();

            assertThat(cacheControl(response)).as(path).containsExactly("no-store");
        }
        assertThat(TRACEABILITY.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void theRuleCoversOnlyTheTraceabilityRoutes() {
        PRODUCTION.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody(BODY)));

        assertThat(cacheControl(client.get().uri("/api/batches").exchange().expectStatus().isOk())).isEmpty();
        assertThat(cacheControl(client.get().uri("/api/traceabilityX/1").exchange().expectStatus().isNotFound())).isEmpty();
    }
}
