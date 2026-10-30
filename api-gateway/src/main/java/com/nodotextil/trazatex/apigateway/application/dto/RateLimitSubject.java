package com.nodotextil.trazatex.apigateway.application.dto;

import com.nodotextil.trazatex.apigateway.domain.model.AuthenticatedUser;

import java.util.Objects;

/** Quién hace la petición, según la ruta: las públicas se limitan por IP; las autenticadas, por usuario y empresa. */
public sealed interface RateLimitSubject {

    /** @param clientKey IP del cliente ya normalizada (IPv6 por /64) */
    record Login(String clientKey) implements RateLimitSubject {
        public Login {
            Objects.requireNonNull(clientKey, "clientKey");
        }
    }

    record Invitation(String clientKey) implements RateLimitSubject {
        public Invitation {
            Objects.requireNonNull(clientKey, "clientKey");
        }
    }

    /** Una petición a una ruta protegida que no trae ninguna credencial válida (sin token o con un token que no es nuestro). */
    record AuthenticationFailure(String clientKey) implements RateLimitSubject {
        public AuthenticationFailure {
            Objects.requireNonNull(clientKey, "clientKey");
        }
    }

    record Authenticated(AuthenticatedUser user) implements RateLimitSubject {
        public Authenticated {
            Objects.requireNonNull(user, "user");
        }
    }
}
