package com.nodotextil.trazatex.apigateway.application.port.in;

import com.nodotextil.trazatex.apigateway.application.dto.RateLimitSubject;
import com.nodotextil.trazatex.apigateway.domain.model.RateLimitDecision;

public interface RateLimitUseCase {

    /** Gasta un permiso de cada cubo que aplica a este sujeto. No bloquea (todo en memoria). */
    RateLimitDecision check(RateLimitSubject subject);
}
