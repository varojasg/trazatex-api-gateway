package com.nodotextil.trazatex.apigateway.infrastructure.security;

import com.nodotextil.trazatex.apigateway.application.port.in.AuthenticateUseCase;
import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationFailedException;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Dos cosas, en este orden, en toda petición enrutada:
 * <ol>
 *   <li>borra las cabeceras {@code X-User-*} y {@code X-Company-*} que traiga el cliente (también en las rutas públicas:
 *       un servicio nunca debe ver una identidad que el Gateway no puso);</li>
 *   <li>si la ruta no es pública, exige un JWT válido y entrega la identidad en {@code X-User-Id}, {@code X-User-Role} y
 *       {@code X-Company-Id}. Sin token válido la petición no llega al servicio: el error sale por
 *       {@code GatewayErrorHandler} (401 o 503). Si una IP acumula demasiadas peticiones sin credencial válida, esas peticiones
 *       pasan a recibir 429 ({@link AuthFailureThrottle}).</li>
 * </ol>
 */
public class AuthenticationGatewayFilter implements GlobalFilter, Ordered {

    /** Antes de cualquier otro filtro global de enrutamiento. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 100;

    /** Atributo del intercambio con el {@code AuthenticatedUser} validado (lo lee el rate limiting). Solo en rutas autenticadas. */
    public static final String USER_ATTRIBUTE = "trazatex.authenticatedUser";

    private final PublicRoutes publicRoutes;
    private final AuthenticateUseCase authenticate;
    private final AuthFailureThrottle authFailureThrottle;

    public AuthenticationGatewayFilter(PublicRoutes publicRoutes, AuthenticateUseCase authenticate, AuthFailureThrottle authFailureThrottle) {
        this.publicRoutes = publicRoutes;
        this.authenticate = authenticate;
        this.authFailureThrottle = authFailureThrottle;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        ServerWebExchange clean = exchange.mutate().request(r -> r.headers(IdentityHeaders::strip)).build();
        if (publicRoutes.isPublic(clean.getRequest())) {
            return chain.filter(clean);
        }
        return authenticate.authenticate(authorization)
                .onErrorResume(AuthenticationFailedException.class, failure -> Mono.error(
                        authFailureThrottle.onFailure(clean.getRequest(), failure).<Throwable>map(limited -> limited).orElse(failure)))
                .flatMap(user -> {
                    clean.getAttributes().put(USER_ATTRIBUTE, user);
                    return chain.filter(clean.mutate().request(r -> r.headers(h -> IdentityHeaders.inject(h, user))).build());
                });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
