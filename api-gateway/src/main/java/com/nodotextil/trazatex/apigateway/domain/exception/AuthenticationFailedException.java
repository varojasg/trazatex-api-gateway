package com.nodotextil.trazatex.apigateway.domain.exception;

/** La petición no trae una identidad válida: se responde 401. El motivo da el {@code code} del error; el detalle no se expone. */
public class AuthenticationFailedException extends RuntimeException {

    public enum Reason {
        MISSING_TOKEN("Falta el token de autenticación"),
        INVALID_TOKEN("El token no es válido"),
        TOKEN_EXPIRED("El token venció, inicia sesión de nuevo"),
        USER_INACTIVE("La cuenta está inactiva");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private final Reason reason;

    public AuthenticationFailedException(Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    public AuthenticationFailedException(Reason reason, Throwable cause) {
        super(reason.name(), cause, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
