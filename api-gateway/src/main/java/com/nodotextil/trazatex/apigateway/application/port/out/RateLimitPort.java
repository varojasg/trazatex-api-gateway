package com.nodotextil.trazatex.apigateway.application.port.out;

import com.nodotextil.trazatex.apigateway.domain.model.RateLimit;
import com.nodotextil.trazatex.apigateway.domain.model.RateLimitDecision;
import com.nodotextil.trazatex.apigateway.domain.model.RateLimitScope;

/** Cubos de permisos por (ámbito, clave). Atómico por cubo: peticiones simultáneas no se cuelan por encima del límite. */
public interface RateLimitPort {

    /** Gasta un permiso del cubo de {@code key} (lo crea lleno si no existe). */
    RateLimitDecision tryConsume(RateLimitScope scope, String key, RateLimit limit);

    /** Devuelve un permiso gastado por una petición que otro cubo terminó rechazando. Nunca pasa de la capacidad. */
    void refund(RateLimitScope scope, String key, RateLimit limit);
}
