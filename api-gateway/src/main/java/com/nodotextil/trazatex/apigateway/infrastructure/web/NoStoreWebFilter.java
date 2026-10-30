package com.nodotextil.trazatex.apigateway.infrastructure.web;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Toda respuesta de {@code /api/traceability/**} lleva {@code Cache-Control: no-store}: lo que se ve de una genealogía depende de la empresa que
 * consulta, y un caché compartido (el ingress, un proxy) podría entregar a una empresa lo calculado para otra. El servicio ya lo pone, pero el
 * Gateway lo impone él mismo, justo antes de enviar la respuesta, para que valga también cuando el servicio lo olvide o responda un cuerpo
 * distinto y para los errores del propio Gateway (401, 429, 503, 504, una ruta rechazada). Corre antes que nada (incluso antes de {@link RequestPathGuardFilter}, para alcanzar sus rechazos) y no depende de la ruta elegida.
 */
@Component
class NoStoreWebFilter implements WebFilter, Ordered {

    static final String PREFIX = "/api/traceability";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getRawPath();
        if (path != null && (path.equals(PREFIX) || path.startsWith(PREFIX + "/"))) {
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
