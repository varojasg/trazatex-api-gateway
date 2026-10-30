package com.nodotextil.trazatex.apigateway.domain.model;

/** Cada ámbito tiene sus propios cubos: agotar uno no afecta a los otros. */
public enum RateLimitScope {
    /** {@code POST /api/auth/login}, por IP. */
    LOGIN_IP,
    /** {@code GET} y {@code POST .../accept} de las invitaciones por enlace, por IP. */
    INVITATION_IP,
    /** Rutas autenticadas, por {@code userId}. */
    USER,
    /** Rutas autenticadas, por empresa (el Dueño de Licencia no tiene). */
    COMPANY,
    /** Peticiones a rutas protegidas sin ninguna credencial válida (sin token o con uno que no es de este sistema), por IP. */
    AUTH_FAILURE_IP
}
