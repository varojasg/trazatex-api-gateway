package com.nodotextil.trazatex.apigateway.infrastructure.security;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;
import java.util.Optional;

public class PublicRoutes {

    /** Qué ruta pública es (para limitar cada una con su cubo). */
    public enum PublicRoute {
        LOGIN,
        INVITATION
    }

    private record Entry(HttpMethod method, PathPattern pattern, PublicRoute route) {
    }

    // Spring 7: un patrón sin barra final no coincide con una ruta que la lleve (/api/auth/login/ no es pública)
    private static final PathPatternParser PARSER = new PathPatternParser();

    private final List<Entry> entries = List.of(
            new Entry(HttpMethod.POST, PARSER.parse("/api/auth/login"), PublicRoute.LOGIN),
            new Entry(HttpMethod.GET, PARSER.parse("/api/invitations/{token}"), PublicRoute.INVITATION),
            new Entry(HttpMethod.POST, PARSER.parse("/api/invitations/{token}/accept"), PublicRoute.INVITATION));

    public boolean isPublic(ServerHttpRequest request) {
        return classify(request).isPresent();
    }

    public Optional<PublicRoute> classify(ServerHttpRequest request) {
        PathContainer path = request.getPath().pathWithinApplication();
        return entries.stream().filter(e -> e.method().equals(request.getMethod()) && e.pattern().matches(path)).map(Entry::route).findFirst();
    }
}
