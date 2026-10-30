package com.nodotextil.trazatex.apigateway.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identidad que el Gateway saca de un JWT válido y entrega a los servicios en {@code X-User-Id}, {@code X-User-Role} y
 * {@code X-Company-Id}. Mismo criterio que organization-access: solo el Dueño de Licencia no tiene empresa.
 */
public record AuthenticatedUser(UUID userId, Role role, UUID companyId) {

    public AuthenticatedUser {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        if (role == Role.LICENSE_OWNER && companyId != null) {
            throw new IllegalArgumentException("El Dueño de Licencia no pertenece a una empresa");
        }
        if (role != Role.LICENSE_OWNER && companyId == null) {
            throw new IllegalArgumentException("El rol " + role + " requiere empresa");
        }
    }
}
