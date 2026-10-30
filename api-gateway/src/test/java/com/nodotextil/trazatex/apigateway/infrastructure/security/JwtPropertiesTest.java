package com.nodotextil.trazatex.apigateway.infrastructure.security;

import com.nodotextil.trazatex.apigateway.infrastructure.config.JwtProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPropertiesTest {

    @Test
    void laRecargaPorKidDesconocidoEsComoMaximoUnaCada30SegundosPorDefecto() {
        JwtProperties props = new JwtProperties("http://localhost:8081/.well-known/jwks.json", null, null, null, null, null, null, null);

        assertThat(props.minReloadInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.failureRetryInterval()).isEqualTo(Duration.ofSeconds(5));
        assertThat(props.cacheTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(props.outageTolerance()).isEqualTo(Duration.ofHours(8));
    }

    @Test
    void sinJwksUriNoArranca() {
        assertThatThrownBy(() -> new JwtProperties(" ", null, null, null, null, null, null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("JWKS_URI");
    }
}
