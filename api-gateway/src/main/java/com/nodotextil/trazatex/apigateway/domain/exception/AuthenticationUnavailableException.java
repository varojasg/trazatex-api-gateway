package com.nodotextil.trazatex.apigateway.domain.exception;

/** No se puede decidir si el token es válido (JWKS inaccesible y sin caché, revocaciones aún sin cargar): se responde 503, no 401. */
public class AuthenticationUnavailableException extends RuntimeException {

    public AuthenticationUnavailableException(String message) {
        super(message);
    }

    public AuthenticationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
