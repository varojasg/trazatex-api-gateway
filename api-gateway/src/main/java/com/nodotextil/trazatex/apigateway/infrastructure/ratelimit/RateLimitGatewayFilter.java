package com.nodotextil.trazatex.apigateway.infrastructure.ratelimit;

import com.nodotextil.trazatex.apigateway.application.dto.RateLimitSubject;
import com.nodotextil.trazatex.apigateway.application.port.in.RateLimitUseCase;
import com.nodotextil.trazatex.apigateway.domain.exception.RateLimitExceededException;
import com.nodotextil.trazatex.apigateway.domain.model.AuthenticatedUser;
import com.nodotextil.trazatex.apigateway.domain.model.RateLimitDecision;
import com.nodotextil.trazatex.apigateway.infrastructure.security.AuthenticationGatewayFilter;
import com.nodotextil.trazatex.apigateway.infrastructure.security.PublicRoutes;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Después de autenticar (necesita saber quién es): rutas públicas por IP (login e invitaciones, cada una con su cubo) y rutas
 * autenticadas por usuario y empresa a la vez. Si se agota un cubo la petición no llega al servicio: 429 con {@code Retry-After}
 * (lo arma {@code GatewayErrorHandler}). Una petición que no pasó la autenticación (401/503) no tiene identidad y no gasta cubos.
 */
public class RateLimitGatewayFilter implements GlobalFilter, Ordered {

    public static final int ORDER = AuthenticationGatewayFilter.ORDER + 100;

    private final RateLimitUseCase rateLimit;
    private final PublicRoutes publicRoutes;
    private final ClientIpResolver clientIps;

    public RateLimitGatewayFilter(RateLimitUseCase rateLimit, PublicRoutes publicRoutes, ClientIpResolver clientIps) {
        this.rateLimit = rateLimit;
        this.publicRoutes = publicRoutes;
        this.clientIps = clientIps;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        RateLimitSubject subject = subjectOf(exchange);
        if (subject != null) {
            RateLimitDecision decision = rateLimit.check(subject);
            if (!decision.allowed()) {
                return Mono.error(new RateLimitExceededException(decision.retryAfter()));
            }
        }
        return chain.filter(exchange);
    }

    private RateLimitSubject subjectOf(ServerWebExchange exchange) {
        AuthenticatedUser user = exchange.getAttribute(AuthenticationGatewayFilter.USER_ATTRIBUTE);
        if (user != null) {
            return new RateLimitSubject.Authenticated(user);
        }
        return publicRoutes.classify(exchange.getRequest())
                .map(route -> switch (route) {
                    case LOGIN -> (RateLimitSubject) new RateLimitSubject.Login(clientIps.resolve(exchange.getRequest()));
                    case INVITATION -> new RateLimitSubject.Invitation(clientIps.resolve(exchange.getRequest()));
                })
                .orElse(null);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
