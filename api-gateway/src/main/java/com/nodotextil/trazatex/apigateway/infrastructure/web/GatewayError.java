package com.nodotextil.trazatex.apigateway.infrastructure.web;

import java.time.Instant;
import java.util.List;

/**
 * Cuerpo de los errores que genera el propio Gateway: el mismo {@code { code, message, details, timestamp }} que el
 * {@code ApiError} del shared-kernel (el Gateway es reactivo y no depende de esa librería).
 */
record GatewayError(String code, String message, List<String> details, Instant timestamp) {

    static GatewayError of(String code, String message) {
        return new GatewayError(code, message, List.of(), Instant.now());
    }
}
