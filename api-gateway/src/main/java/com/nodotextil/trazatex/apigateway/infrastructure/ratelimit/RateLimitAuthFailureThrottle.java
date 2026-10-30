package com.nodotextil.trazatex.apigateway.infrastructure.ratelimit;

import com.nodotextil.trazatex.apigateway.application.dto.RateLimitSubject;
import com.nodotextil.trazatex.apigateway.application.port.in.RateLimitUseCase;
import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationFailedException;
import com.nodotextil.trazatex.apigateway.domain.exception.RateLimitExceededException;
import com.nodotextil.trazatex.apigateway.domain.model.RateLimitDecision;
import com.nodotextil.trazatex.apigateway.infrastructure.security.AuthFailureThrottle;
import org.springframework.http.server.reactive.ServerHttpRequest;

import java.util.Optional;

/**
 * Cubo por IP de las peticiones sin credencial válida. Solo cuentan {@code MISSING_TOKEN} e {@code INVALID_TOKEN}: ahí no hay
 * ninguna identidad genuina detrás (sin token, o uno que no firmó organization-access). {@code TOKEN_EXPIRED} y
 * {@code USER_INACTIVE} traen una firma válida: son usuarios reales con la sesión vencida o la cuenta inactivada, y al terminar
 * un turno puede haber decenas detrás de la misma IP de la planta; no son un ataque ni deben agotar este cubo.
 *
 * <p>Una petición con token válido nunca pasa por aquí: un tercero que inunde de basura desde la IP de la planta no bloquea a sus
 * operarios.
 */
public class RateLimitAuthFailureThrottle implements AuthFailureThrottle {

    private final RateLimitUseCase rateLimit;
    private final ClientIpResolver clientIps;

    public RateLimitAuthFailureThrottle(RateLimitUseCase rateLimit, ClientIpResolver clientIps) {
        this.rateLimit = rateLimit;
        this.clientIps = clientIps;
    }

    @Override
    public Optional<RateLimitExceededException> onFailure(ServerHttpRequest request, AuthenticationFailedException failure) {
        if (failure.reason() != AuthenticationFailedException.Reason.MISSING_TOKEN
                && failure.reason() != AuthenticationFailedException.Reason.INVALID_TOKEN) {
            return Optional.empty();
        }
        RateLimitDecision decision = rateLimit.check(new RateLimitSubject.AuthenticationFailure(clientIps.resolve(request)));
        return decision.allowed() ? Optional.empty() : Optional.of(new RateLimitExceededException(decision.retryAfter()));
    }
}
