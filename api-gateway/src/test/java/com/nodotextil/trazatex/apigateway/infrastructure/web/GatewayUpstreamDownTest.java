package com.nodotextil.trazatex.apigateway.infrastructure.web;

import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

/** Servicio caído (puerto cerrado): 503 con {@code ApiError}, sin filtrar host ni puerto al cliente. */
@TestPropertySource(properties = "trazatex.gateway.services.organization-access-url=http://localhost:${closed.port}")
class GatewayUpstreamDownTest extends GatewayTestSupport {

    private static final int CLOSED_PORT = closedPort();

    static {
        System.setProperty("closed.port", String.valueOf(CLOSED_PORT));
    }

    @Test
    void unServicioCaidoDa503() {
        client.get().uri("/api/license").exchange().expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.code").isEqualTo("SERVICE_UNAVAILABLE")
                .jsonPath("$.message").value(m -> assertThat(m.toString()).doesNotContain("localhost", "127.0.0.1", String.valueOf(CLOSED_PORT)));
    }

    private static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
