package com.nodotextil.trazatex.apigateway.infrastructure.security;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tiempo controlado a mano ({@code currentTime}): sin esperas. */
class StrictRateLimitedJWKSetSourceTest {

    private static final long INTERVAL = 30_000;
    private static final long RETRY = 5_000;

    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean failing;
    private final JWKSetSource<SecurityContext> origin = new JWKSetSource<>() {
        @Override
        public JWKSet getJWKSet(JWKSetCacheRefreshEvaluator evaluator, long now, SecurityContext ctx) throws KeySourceException {
            calls.incrementAndGet();
            if (failing) {
                throw new KeySourceException("org-access caído");
            }
            return new JWKSet();
        }

        @Override
        public void close() {
        }
    };
    private final StrictRateLimitedJWKSetSource<SecurityContext> source = new StrictRateLimitedJWKSetSource<>(origin, INTERVAL, RETRY);

    private JWKSet get(long now) throws KeySourceException {
        return source.getJWKSet(JWKSetCacheRefreshEvaluator.forceRefresh(), now, null);
    }

    @Test
    void despuesDeUnaCargaExitosaNoHayOtraAntesDelIntervaloNiSiquieraLaPrimeraRecarga() throws Exception {
        get(0);
        // a diferencia del limitador de Nimbus (que deja pasar una segunda descarga inmediata)
        assertThatThrownBy(() -> get(1)).isInstanceOf(RateLimitReachedException.class);
        assertThatThrownBy(() -> get(15_000)).isInstanceOf(RateLimitReachedException.class);
        assertThatThrownBy(() -> get(INTERVAL - 1)).isInstanceOf(RateLimitReachedException.class);
        assertThat(calls).hasValue(1);

        get(INTERVAL);
        assertThat(calls).hasValue(2);
        assertThatThrownBy(() -> get(INTERVAL + 1)).isInstanceOf(RateLimitReachedException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void unaRafagaDeUnKidDesconocidoProvocaComoMuchoUnaDescargaPorIntervalo() throws Exception {
        get(0);
        int rejected = 0;
        for (long t = 1; t < 10 * INTERVAL; t += 100) {
            try {
                get(t);
            } catch (RateLimitReachedException e) {
                rejected++;
            }
        }
        // 3000 intentos en 10 intervalos: la descarga inicial + una por intervalo (9 más, la del instante 10*INTERVAL no entra)
        assertThat(calls).hasValue(10);
        assertThat(rejected).isEqualTo(3000 - 9);
    }

    @Test
    void sinCargaPreviaUnFalloSeReintentaALosCincoSegundosYMientrasTantoSigueSiendoElFallo() throws Exception {
        failing = true;
        assertThatThrownBy(() -> get(0)).isInstanceOf(KeySourceException.class).isNotInstanceOf(RateLimitReachedException.class);
        // dentro del bloqueo se repite el fallo original (503), no un RateLimitReached (que sería un 401 a un token quizá bueno)
        assertThatThrownBy(() -> get(RETRY - 1)).isInstanceOf(KeySourceException.class).isNotInstanceOf(RateLimitReachedException.class)
                .hasMessage("org-access caído");
        assertThat(calls).hasValue(1);

        failing = false;
        get(RETRY);
        assertThat(calls).hasValue(2);
        // ya cargado: rige el intervalo completo
        assertThatThrownBy(() -> get(RETRY + 1)).isInstanceOf(RateLimitReachedException.class);
    }

    @Test
    void conUnaCargaPreviaUnFalloDeRefrescoEsperaElIntervaloCompleto() throws Exception {
        get(0);
        failing = true;
        assertThatThrownBy(() -> get(INTERVAL)).isInstanceOf(KeySourceException.class).isNotInstanceOf(RateLimitReachedException.class);
        assertThatThrownBy(() -> get(INTERVAL + RETRY)).isInstanceOf(RateLimitReachedException.class);
        assertThat(calls).hasValue(2);
        get2(2 * INTERVAL);
        assertThat(calls).hasValue(3);
    }

    private void get2(long now) {
        assertThatThrownBy(() -> get(now)).isInstanceOf(KeySourceException.class);
    }

    @Test
    void elReintentoPorFalloNuncaSuperaElIntervalo() throws Exception {
        StrictRateLimitedJWKSetSource<SecurityContext> s = new StrictRateLimitedJWKSetSource<>(origin, 2_000, 60_000);
        failing = true;
        assertThatThrownBy(() -> s.getJWKSet(JWKSetCacheRefreshEvaluator.forceRefresh(), 0, null)).isInstanceOf(KeySourceException.class);
        failing = false;
        s.getJWKSet(JWKSetCacheRefreshEvaluator.forceRefresh(), 2_000, null);
        assertThat(calls).hasValue(2);
    }
}
