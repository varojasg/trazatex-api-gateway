package com.nodotextil.trazatex.apigateway.infrastructure.security;

import com.nimbusds.jose.jwk.RSAKey;
import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import com.nodotextil.trazatex.apigateway.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * El JWKS se guarda en caché y ante un {@code kid} desconocido se vuelve a pedir <b>como máximo una vez por intervalo</b>
 * (30 s en producción; aquí 2 s para poder probarlo). Un atacante que mande tokens con kids inventados no puede hacer que el
 * Gateway martille a organization-access.
 */
@TestPropertySource(properties = {"trazatex.jwt.min-reload-interval=PT2S", "test.case=jwks-reload"})
class JwksReloadTest extends GatewayTestSupport {

    private static final long INTERVAL_MS = 2_000;

    private int jwksRequests() {
        return JWKS.countRequestsMatching(getRequestedFor(urlEqualTo(JWKS_PATH)).build()).getCount();
    }

    private int status(String token) {
        return anonymous.get().uri("/api/license").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .returnResult(String.class).getStatus().value();
    }

    private void publish(RSAKey... keys) {
        JWKS.stubFor(get(urlEqualTo(JWKS_PATH)).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(TestTokens.jwks(keys))));
    }

    @Test
    void elJwksSeCacheaYUnKidDesconocidoSoloLoRecargaUnaVezPorIntervalo() throws InterruptedException {
        ORG_ACCESS.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
        RSAKey key2 = TestTokens.generate("kid-2");
        RSAKey key3 = TestTokens.generate("kid-3");
        String token1 = TestTokens.operator();
        String token2 = TestTokens.sign(key2, TestTokens.operatorClaims().build());
        String token3 = TestTokens.sign(key3, TestTokens.operatorClaims().build());

        // 1) caché: 20 peticiones con el kid conocido = una sola descarga
        for (int i = 0; i < 20; i++) {
            assertThat(status(token1)).isEqualTo(200);
        }
        assertThat(jwksRequests()).as("una descarga, el resto sale de la caché").isEqualTo(1);

        // 2) kid-2 aún no está en el JWKS y estamos dentro del intervalo: 401 y NO se vuelve a pedir
        for (int i = 0; i < 5; i++) {
            assertThat(status(token2)).isEqualTo(401);
        }
        assertThat(jwksRequests()).as("dentro del intervalo no se recarga").isEqualTo(1);

        // 3) la clave se publica, pero seguimos dentro del intervalo: todavía 401 (la espera de hasta 30 s es el costo del límite)
        publish(TestTokens.KEY, key2);
        assertThat(status(token2)).isEqualTo(401);
        assertThat(jwksRequests()).isEqualTo(1);

        // 4) pasado el intervalo, el primer token con kid-2 provoca la recarga y entra; los siguientes salen de la caché
        Thread.sleep(INTERVAL_MS + 200);
        assertThat(status(token2)).isEqualTo(200);
        assertThat(jwksRequests()).as("una recarga").isEqualTo(2);
        for (int i = 0; i < 5; i++) {
            assertThat(status(token2)).isEqualTo(200);
        }
        assertThat(jwksRequests()).isEqualTo(2);

        // 5) kid-3 nunca existirá: ráfaga inmediata = sin recarga (acaba de recargarse)
        for (int i = 0; i < 10; i++) {
            assertThat(status(token3)).isEqualTo(401);
        }
        assertThat(jwksRequests()).isEqualTo(2);

        // 6) pasado otro intervalo, una ráfaga de kid-3 provoca exactamente UNA recarga
        Thread.sleep(INTERVAL_MS + 200);
        for (int i = 0; i < 10; i++) {
            assertThat(status(token3)).isEqualTo(401);
        }
        assertThat(jwksRequests()).as("una sola recarga para toda la ráfaga").isEqualTo(3);

        // y mientras tanto los tokens buenos siguen entrando
        assertThat(status(token1)).isEqualTo(200);
    }
}
