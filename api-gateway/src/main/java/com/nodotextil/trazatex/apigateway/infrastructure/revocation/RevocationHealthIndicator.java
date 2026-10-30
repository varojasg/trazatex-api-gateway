package com.nodotextil.trazatex.apigateway.infrastructure.revocation;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Parte del grupo {@code readiness}: el Gateway no está listo mientras no conozca la lista de usuarios inactivos. Sale como
 * {@code OUT_OF_SERVICE} (503), no {@code DOWN}: no es una falla, es que está cargando.
 */
public class RevocationHealthIndicator implements HealthIndicator {

    private final RevokedUsersRegistry registry;

    public RevocationHealthIndicator(RevokedUsersRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        if (registry.isLoaded()) {
            return Health.up().withDetail("inactiveUsers", registry.inactiveCount()).build();
        }
        return Health.outOfService().withDetail("state", "LOADING").build();
    }
}
