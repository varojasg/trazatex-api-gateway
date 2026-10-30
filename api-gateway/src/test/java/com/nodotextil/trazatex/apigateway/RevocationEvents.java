package com.nodotextil.trazatex.apigateway;

import java.util.UUID;

public final class RevocationEvents {

    private RevocationEvents() {
    }

    public static String userUpdated(UUID userId, String status) {
        return """
                {"eventId":"%s","type":"UserAccountUpdated","aggregateId":"%s","version":1,"occurredAt":"2026-10-20T10:00:00Z",
                 "payload":{"userId":"%s","email":"%s@hilos.pe","fullName":"Persona Prueba","role":"OPERATOR","companyId":"%s","status":"%s"}}"""
                .formatted(UUID.randomUUID(), userId, userId, userId.toString().substring(0, 8), TestTokens.COMPANY_ID, status);
    }

    /** Igual, pero con campos que el Gateway no conoce (organization-access podría agregarlos en el futuro). */
    public static String userUpdatedWithUnknownFields(UUID userId, String status) {
        return """
                {"eventId":"%s","type":"UserAccountUpdated","aggregateId":"%s","version":2,"occurredAt":"2026-10-20T10:00:00Z","traceId":"abc",
                 "payload":{"userId":"%s","email":"x@hilos.pe","fullName":"Persona Prueba","role":"OPERATOR","companyId":"%s","status":"%s",
                            "phone":"+51999999999","preferences":{"lang":"es","tags":[1,2,3]},"deactivatedBy":null}}"""
                .formatted(UUID.randomUUID(), userId, userId, TestTokens.COMPANY_ID, status);
    }
}
