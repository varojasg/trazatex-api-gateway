package com.nodotextil.trazatex.apigateway.infrastructure.web;

import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationFailedException;
import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationUnavailableException;
import com.nodotextil.trazatex.apigateway.domain.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

/**
 * Convierte en {@link GatewayError} lo que falla en el Gateway (ruta inexistente, servicio caído o lento) para que el
 * frontend siempre reciba el mismo formato. El motivo interno (host, puerto, excepción) va al log, nunca al cliente.
 * Corre antes que el manejador por defecto de Spring Boot ({@code -1}).
 */
@Component
@Order(-2)
class GatewayErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    private final JsonMapper json;

    GatewayErrorHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        if (ex instanceof RateLimitExceededException limited) {
            exchange.getResponse().getHeaders().set(HttpHeaders.RETRY_AFTER, String.valueOf(limited.retryAfterSeconds()));
            return reject(exchange, HttpStatus.TOO_MANY_REQUESTS, GatewayError.of("TOO_MANY_REQUESTS",
                    "Demasiadas solicitudes, intenta de nuevo en %d segundos".formatted(limited.retryAfterSeconds())));
        }
        if (ex instanceof AuthenticationFailedException failed) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, GatewayError.of(failed.reason().name(), failed.reason().message()));
        }
        if (ex instanceof AuthenticationUnavailableException) {
            log.warn("{} {} -> 503: no se puede validar el token ({})", exchange.getRequest().getMethod(), exchange.getRequest().getPath(), ex.getMessage());
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    GatewayError.of("AUTH_UNAVAILABLE", "No se puede validar la sesión en este momento, intenta de nuevo en unos segundos"));
        }
        HttpStatusCode status = statusOf(ex);
        GatewayError error = describe(status);
        if (status.is5xxServerError()) {
            log.warn("{} {} -> {}: {}", exchange.getRequest().getMethod(), exchange.getRequest().getPath(), status.value(), ex.toString());
        }
        return reject(exchange, status, error);
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatusCode status, GatewayError error) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status.value() == 401) {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return exchange.getResponse().writeWith(Mono.fromSupplier(() -> body(exchange, error)));
    }

    /** Un servicio al que no se pudo conectar (caído, host desconocido) es 503; uno que cortó la conexión a mitad, 502. */
    private static HttpStatusCode statusOf(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ResponseStatusException rse) {
                return rse.getStatusCode();
            }
            if (t instanceof ConnectException || t instanceof UnknownHostException) {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
            if (t instanceof PrematureCloseException) {
                return HttpStatus.BAD_GATEWAY;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static GatewayError describe(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> GatewayError.of("ROUTE_NOT_FOUND", "La ruta solicitada no existe");
            case 405 -> GatewayError.of("METHOD_NOT_ALLOWED", "Método no permitido");
            case 502, 503 -> GatewayError.of("SERVICE_UNAVAILABLE", "El servicio no está disponible, intenta de nuevo en unos segundos");
            case 504 -> GatewayError.of("GATEWAY_TIMEOUT", "El servicio tardó demasiado en responder");
            default -> status.is4xxClientError()
                    ? GatewayError.of("BAD_REQUEST", "Solicitud inválida")
                    : GatewayError.of("INTERNAL_ERROR", "Error interno del Gateway");
        };
    }

    private DataBuffer body(ServerWebExchange exchange, GatewayError error) {
        try {
            return exchange.getResponse().bufferFactory().wrap(json.writeValueAsBytes(error));
        } catch (JacksonException e) {
            return exchange.getResponse().bufferFactory().wrap("{\"code\":\"INTERNAL_ERROR\"}".getBytes(StandardCharsets.UTF_8));
        }
    }
}
