package com.nodotextil.trazatex.apigateway.infrastructure.web;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
class RequestPathGuardFilter implements WebFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String rawPath = exchange.getRequest().getURI().getRawPath();
        if (rawPath == null || rawPath.isEmpty()) {
            return chain.filter(exchange);
        }
        String lower = rawPath.toLowerCase(Locale.ROOT);
        if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00") || lower.contains("%3b")
                || lower.contains("\\") || lower.contains(";") || lower.contains("//")) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST));
        }
        String[] segments = rawPath.split("/", -1);
        for (int i = 1; i < segments.length; i++) {
            String segment;
            try {
                segment = UriUtils.decode(segments[i], StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST));
            }
            if (segment.equals(".") || segment.equals("..")) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST));
            }
        }
        if ("internal".equalsIgnoreCase(UriUtils.decode(segments.length > 1 ? segments[1] : "", StandardCharsets.UTF_8))) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1; // justo después de NoStoreWebFilter, que no decide nada: solo registra la cabecera de sus respuestas
    }
}
