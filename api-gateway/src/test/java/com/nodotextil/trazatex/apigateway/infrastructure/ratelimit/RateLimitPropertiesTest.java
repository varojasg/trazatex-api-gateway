package com.nodotextil.trazatex.apigateway.infrastructure.ratelimit;

import com.nodotextil.trazatex.apigateway.infrastructure.config.RateLimitProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitPropertiesTest {

    private static RateLimitProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties)).bind("trazatex.rate-limit", RateLimitProperties.class)
                .orElseGet(() -> new RateLimitProperties(null, null, null, null, null, null, null, null, null));
    }

    private static Map<String, String> properties(String... keyValues) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void theDefaultsAreTheAgreedLimits() {
        RateLimitProperties p = bind(Map.of());

        assertThat(p.login().permits()).isEqualTo(60);
        assertThat(p.invitation().permits()).isEqualTo(30);
        assertThat(p.user().permits()).isEqualTo(120);
        assertThat(p.company().permits()).isEqualTo(600);
        assertThat(p.authFailure().permits()).isEqualTo(300);
        assertThat(p.login().window()).isEqualTo(Duration.ofMinutes(1));
        assertThat(p.idleExpiry()).isEqualTo(Duration.ofMinutes(10));
        assertThat(p.maxEntries()).isEqualTo(100_000);
        assertThat(p.trustForwardedFor()).as("desactivado por defecto").isFalse();
        assertThat(p.trustedHops()).isEqualTo(1);
    }

    @Test
    void everyLimitCanBeConfiguredWithProperties() {
        RateLimitProperties p = bind(properties(
                "trazatex.rate-limit.login.permits", "5",
                "trazatex.rate-limit.invitation.permits", "6", "trazatex.rate-limit.invitation.window", "PT30S",
                "trazatex.rate-limit.user.permits", "7",
                "trazatex.rate-limit.auth-failure.permits", "11", "trazatex.rate-limit.auth-failure.window", "PT2M",
                "trazatex.rate-limit.company.window", "PT5M", "trazatex.rate-limit.company.permits", "9",
                "trazatex.rate-limit.idle-expiry", "PT20M",
                "trazatex.rate-limit.max-entries", "50",
                "trazatex.rate-limit.trust-forwarded-for", "true",
                "trazatex.rate-limit.trusted-hops", "2"));

        assertThat(p.login().permits()).isEqualTo(5);
        assertThat(p.login().window()).as("lo que no se configura queda por defecto").isEqualTo(Duration.ofMinutes(1));
        assertThat(p.invitation().permits()).isEqualTo(6);
        assertThat(p.invitation().window()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.user().permits()).isEqualTo(7);
        assertThat(p.authFailure().permits()).isEqualTo(11);
        assertThat(p.authFailure().window()).isEqualTo(Duration.ofMinutes(2));
        assertThat(p.company().permits()).isEqualTo(9);
        assertThat(p.company().window()).isEqualTo(Duration.ofMinutes(5));
        assertThat(p.idleExpiry()).isEqualTo(Duration.ofMinutes(20));
        assertThat(p.maxEntries()).isEqualTo(50);
        assertThat(p.trustForwardedFor()).isTrue();
        assertThat(p.trustedHops()).isEqualTo(2);
    }

    @Test
    void theInactivityExpiryCannotBeShorterThanTheLongestWindow() {
        assertThatThrownBy(() -> bind(Map.of("trazatex.rate-limit.idle-expiry", "PT30S")))
                .hasRootCauseInstanceOf(IllegalStateException.class).hasRootCauseMessage(
                        "trazatex.rate-limit.idle-expiry (PT30S) no puede ser menor que la ventana más larga (PT1M): un cubo que expira antes de rellenarse volvería a nacer lleno");
        assertThatThrownBy(() -> bind(Map.of("trazatex.rate-limit.company.window", "PT1H")))
                .hasRootCauseMessage(
                        "trazatex.rate-limit.idle-expiry (PT10M) no puede ser menor que la ventana más larga (PT1H): un cubo que expira antes de rellenarse volvería a nacer lleno");
    }

    @Test
    void invalidValuesStopTheStartupWithAClearMessage() {
        assertThatThrownBy(() -> bind(Map.of("trazatex.rate-limit.user.permits", "0"))).hasRootCauseMessage("trazatex.rate-limit.user.permits debe ser al menos 1");
        assertThatThrownBy(() -> bind(Map.of("trazatex.rate-limit.auth-failure.permits", "0")))
                .hasRootCauseMessage("trazatex.rate-limit.auth-failure.permits debe ser al menos 1");
        assertThatThrownBy(() -> bind(Map.of("trazatex.rate-limit.login.window", "PT0S"))).hasRootCauseMessage("trazatex.rate-limit.login.window debe ser al menos 1 s");
        assertThatThrownBy(() -> bind(Map.of("trazatex.rate-limit.max-entries", "0"))).hasRootCauseMessage("trazatex.rate-limit.max-entries debe ser al menos 1");
        assertThatThrownBy(() -> bind(Map.of("trazatex.rate-limit.trusted-hops", "0"))).hasRootCauseMessage("trazatex.rate-limit.trusted-hops debe ser al menos 1");
    }
}
