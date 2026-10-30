package com.nodotextil.trazatex.apigateway.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Rate limiting en memoria (Bucket4j). Todos los límites son configurables.
 *
 * <p>{@code authFailure}: peticiones por IP a rutas protegidas <b>sin credencial válida</b> (sin token o con uno que no es de este
 * sistema); pasado el límite, esas peticiones reciben 429 en vez de 401. No frena a quien sí trae un token válido desde la misma IP.
 *
 * <p>Los cubos viven en cachés Caffeine con expiración por <b>inactividad</b> ({@code idleExpiry}, 10 min) y un tope de entradas
 * ({@code maxEntries}) para que la memoria no crezca sin límite. {@code idleExpiry} no puede ser menor que la ventana más larga:
 * un cubo que expira antes de rellenarse volvería a nacer lleno y se saltaría el límite.
 *
 * <p>La IP de las rutas públicas sale de la <b>dirección remota de la conexión</b>, nunca de un {@code X-Forwarded-For} que mande
 * el cliente. Solo con {@code trustForwardedFor=true} (Azure: el ingress agrega el valor y es el único en quien se confía) se
 * usa ese encabezado, y se toma el valor que agrega el ingress: el de más a la derecha ({@code trustedHops}-ésimo contando desde
 * la derecha), nunca el de más a la izquierda, que es el que controla el cliente.
 */
@ConfigurationProperties("trazatex.rate-limit")
public record RateLimitProperties(
        Rule login,
        Rule invitation,
        Rule user,
        Rule company,
        Rule authFailure,
        Duration idleExpiry,
        Long maxEntries,
        Boolean trustForwardedFor,
        Integer trustedHops) {

    /** Se puede sobrescribir solo {@code permits} o solo {@code window}: lo que falte toma el valor por defecto de la regla. */
    public record Rule(Long permits, Duration window) {

        static Rule resolve(String name, Rule configured, long defaultPermits) {
            long permits = configured == null || configured.permits() == null ? defaultPermits : configured.permits();
            Duration window = configured == null || configured.window() == null ? Duration.ofMinutes(1) : configured.window();
            if (permits < 1) {
                throw new IllegalStateException("trazatex.rate-limit." + name + ".permits debe ser al menos 1");
            }
            if (window.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new IllegalStateException("trazatex.rate-limit." + name + ".window debe ser al menos 1 s");
            }
            return new Rule(permits, window);
        }
    }

    public RateLimitProperties {
        login = Rule.resolve("login", login, 60);
        invitation = Rule.resolve("invitation", invitation, 30);
        user = Rule.resolve("user", user, 120);
        company = Rule.resolve("company", company, 600);
        authFailure = Rule.resolve("auth-failure", authFailure, 300);
        idleExpiry = idleExpiry == null ? Duration.ofMinutes(10) : idleExpiry;
        maxEntries = maxEntries == null ? 100_000L : maxEntries;
        trustForwardedFor = trustForwardedFor != null && trustForwardedFor;
        trustedHops = trustedHops == null ? 1 : trustedHops;
        if (maxEntries < 1) {
            throw new IllegalStateException("trazatex.rate-limit.max-entries debe ser al menos 1");
        }
        if (trustedHops < 1) {
            throw new IllegalStateException("trazatex.rate-limit.trusted-hops debe ser al menos 1");
        }
        Duration longest = java.util.stream.Stream.of(login, invitation, user, company, authFailure).map(Rule::window).max(Duration::compareTo).orElseThrow();
        if (idleExpiry.compareTo(longest) < 0) {
            throw new IllegalStateException("trazatex.rate-limit.idle-expiry (" + idleExpiry + ") no puede ser menor que la ventana más larga ("
                    + longest + "): un cubo que expira antes de rellenarse volvería a nacer lleno");
        }
    }
}
