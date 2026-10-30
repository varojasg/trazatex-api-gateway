package com.nodotextil.trazatex.apigateway.infrastructure.revocation;

import com.nodotextil.trazatex.apigateway.application.port.out.RevokedUsersPort;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Conjunto en memoria de los {@code userId} inactivos. Lo escribe un solo hilo (el consumidor) y lo leen todos los de peticiones. */
public class RevokedUsersRegistry implements RevokedUsersPort {

    private final Set<UUID> inactive = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean loaded;

    /** @param loaded {@code true} solo si no hay nada que cargar (revocación apagada en pruebas) */
    public RevokedUsersRegistry(boolean loaded) {
        this.loaded = new AtomicBoolean(loaded);
    }

    @Override
    public boolean isLoaded() {
        return loaded.get();
    }

    @Override
    public boolean isRevoked(UUID userId) {
        return inactive.contains(userId);
    }

    /** Idempotente: aplicar el mismo estado dos veces (relectura, duplicados) deja lo mismo. */
    void apply(UUID userId, boolean inactiveNow) {
        if (inactiveNow) {
            inactive.add(userId);
        } else {
            inactive.remove(userId);
        }
    }

    void markLoaded() {
        loaded.set(true);
    }

    int inactiveCount() {
        return inactive.size();
    }
}
