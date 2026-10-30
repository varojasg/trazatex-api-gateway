package com.nodotextil.trazatex.apigateway.infrastructure.web;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.reactive.function.BodyInserters;

import java.time.Duration;
import java.util.Arrays;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 04f-1: las rutas de production llegan a production tal cual, y una subida de evidencias (hasta 5 archivos de 10 MB) pasa íntegra: el
 * Gateway no lee ni limita el cuerpo; quien rechaza un archivo de más de 10 MB es production, con su ApiError 413, que el Gateway deja pasar.
 */
class ProductionRoutingTest extends GatewayTestSupport {

    private static final int TEN_MB = 10 * 1024 * 1024;

    @BeforeEach
    void stubProduction() {
        PRODUCTION.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"ok\":true}")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/batches", "/api/batches/qr/XYZ", "/api/batches/1/split", "/api/batches/1/commercial-data", "/api/batches/1/evidence",
            "/api/machines", "/api/machines/1/status", "/api/transformations/1/complete", "/api/transformations/1/evidence",
            "/api/transfers/2/accept"})
    void rutasDeProductionLleganTalCual(String path) {
        client.post().uri(path + "?page=1").contentType(MediaType.APPLICATION_JSON).bodyValue("{\"a\":1}").exchange().expectStatus().isOk();

        LoggedRequest received = PRODUCTION.findAll(anyRequestedFor(anyUrl())).getFirst();
        assertThat(received.getUrl()).isEqualTo(path + "?page=1");
        assertThat(received.header("X-User-Id").isPresent()).as("el Gateway inyecta la identidad").isTrue();
        assertThat(received.header("X-User-Role").firstValue()).isEqualTo("OPERATOR");
        assertThat(ORG_ACCESS.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    private static byte[] bytesOfSize(int size, int seed) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (i * 31 + seed);
        }
        return bytes;
    }

    @Test
    void cincoArchivosDeDiezMegabytesPasanIntegros() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        byte[][] files = new byte[5][];
        for (int i = 0; i < 5; i++) {
            files[i] = bytesOfSize(TEN_MB, i);
            builder.part("files", new ByteArrayResource(files[i]) {
                @Override
                public String getFilename() {
                    return "foto" + "-" + System.nanoTime() + ".jpg";
                }
            }).contentType(MediaType.IMAGE_JPEG);
        }

        client.mutate().responseTimeout(Duration.ofSeconds(60)).build().post().uri("/api/batches/1/evidence")
                .header("Idempotency-Key", "6f0c5b0e-6e89-4b43-9d1c-0f1a2b3c4d5e")
                .body(BodyInserters.fromMultipartData(builder.build())).exchange().expectStatus().isOk();

        LoggedRequest received = PRODUCTION.findAll(anyRequestedFor(anyUrl())).getFirst();
        assertThat(received.getHeader(HttpHeaders.CONTENT_TYPE)).startsWith("multipart/form-data;boundary=").contains("boundary=");
        assertThat(received.header("Idempotency-Key").firstValue()).isEqualTo("6f0c5b0e-6e89-4b43-9d1c-0f1a2b3c4d5e");
        assertThat(received.header("X-User-Id").isPresent()).isTrue();
        assertThat(received.getParts()).hasSize(5);
        int index = 0;
        for (var part : received.getParts()) {
            assertThat(part.getName()).isEqualTo("files");
            assertThat(Arrays.equals(part.getBody().asBytes(), files[index++])).as("los bytes de la foto " + index + " llegaron iguales").isTrue();
        }
    }

    @Test
    void unArchivoDeMasDeDiezMegabytesTambienPasaYLaRespuesta413DeProductionLlegaIntacta() {
        String apiError = "{\"code\":\"FILE_TOO_LARGE\",\"message\":\"Cada fotografía puede pesar como máximo 10 MB (y una petición, 5 fotografías).\",\"details\":[],\"timestamp\":\"2026-10-21T10:00:00Z\"}";
        PRODUCTION.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(413).withHeader("Content-Type", "application/json").withBody(apiError)));
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("files", new ByteArrayResource(bytesOfSize(TEN_MB + 1, 9)) {
            @Override
            public String getFilename() {
                return "enorme.jpg";
            }
        }).contentType(MediaType.IMAGE_JPEG);

        client.mutate().responseTimeout(Duration.ofSeconds(60)).build().post().uri("/api/transformations/1/evidence")
                .body(BodyInserters.fromMultipartData(builder.build())).exchange()
                .expectStatus().isEqualTo(413)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("FILE_TOO_LARGE").jsonPath("$.timestamp").isNotEmpty();

        LoggedRequest received = PRODUCTION.findAll(anyRequestedFor(anyUrl())).getFirst();
        assertThat(received.getParts().iterator().next().getBody().asBytes()).as("el Gateway no recortó el archivo").hasSize(TEN_MB + 1);
    }

    @Test
    void sinTokenLasRutasDeProductionDan401YNoLlegan() {
        anonymous.get().uri("/api/batches").exchange().expectStatus().isUnauthorized();

        assertThat(PRODUCTION.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void internalSigueSinEnrutarseHaciaProduction() {
        client.post().uri("/internal/batches/1/phase-transitions").exchange().expectStatus().isNotFound();
        client.post().uri("/api/batches/../../internal/batches/1/phase-transitions").exchange().expectStatus().isBadRequest();

        assertThat(PRODUCTION.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }
}
