package com.nodotextil.trazatex.apigateway.infrastructure.security;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSetCacheRefreshEvaluator;
import com.nimbusds.jose.jwk.source.JWKSetSource;
import com.nimbusds.jose.jwk.source.JWKSetSourceWrapper;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.SecurityContext;

/**
 * Límite <b>estricto</b> de descargas del JWKS: una vez cargado con éxito, no se vuelve a pedir hasta pasados
 * {@code minInterval} (30 s), sea porque venció la caché o porque llegó un token con un {@code kid} desconocido. Un atacante que
 * invente kids no puede hacer que el Gateway martille a organization-access.
 *
 * <p>El limitador de Nimbus ({@code JWKSourceBuilder.rateLimited}) no sirve para esto: es un cubo con una ficha de reserva y
 * deja pasar <b>dos</b> descargas por ventana (la inicial y una recarga inmediata).
 *
 * <p>Mientras nunca se haya cargado el JWKS (arranque con organization-access caído) un fallo solo bloquea
 * {@code failureRetryInterval}, y durante ese bloqueo se vuelve a lanzar el fallo original (el Gateway responde 503, no 401): el
 * token puede ser bueno y no hay con qué comprobarlo.
 */
public class StrictRateLimitedJWKSetSource<C extends SecurityContext> extends JWKSetSourceWrapper<C> {

    private final long minIntervalMillis;
    private final long failureRetryMillis;

    private long nextAllowed = Long.MIN_VALUE;
    private boolean loadedOnce;
    private KeySourceException lastFailure;

    public StrictRateLimitedJWKSetSource(JWKSetSource<C> source, long minIntervalMillis, long failureRetryMillis) {
        super(source);
        this.minIntervalMillis = minIntervalMillis;
        this.failureRetryMillis = Math.min(failureRetryMillis, minIntervalMillis);
    }

    /** Sincronizado a propósito: las peticiones simultáneas esperan a la misma descarga en vez de hacer una cada una. */
    @Override
    public synchronized JWKSet getJWKSet(JWKSetCacheRefreshEvaluator refreshEvaluator, long currentTime, C context) throws KeySourceException {
        if (currentTime < nextAllowed) {
            if (!loadedOnce && lastFailure != null) {
                throw lastFailure;
            }
            throw new RateLimitReachedException();
        }
        try {
            JWKSet jwkSet = getSource().getJWKSet(refreshEvaluator, currentTime, context);
            nextAllowed = currentTime + minIntervalMillis;
            loadedOnce = true;
            lastFailure = null;
            return jwkSet;
        } catch (KeySourceException e) {
            nextAllowed = currentTime + (loadedOnce ? minIntervalMillis : failureRetryMillis);
            lastFailure = e;
            throw e;
        }
    }
}
