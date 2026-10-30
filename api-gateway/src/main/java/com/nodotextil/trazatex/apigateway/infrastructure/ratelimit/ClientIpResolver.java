package com.nodotextil.trazatex.apigateway.infrastructure.ratelimit;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.regex.Pattern;

/**
 * La IP con la que se limitan las rutas públicas. Por defecto es la <b>dirección remota de la conexión</b>: un
 * {@code X-Forwarded-For} que mande el cliente no se mira, así que no sirve para cambiar de cubo ni para esquivar el límite.
 *
 * <p>Detrás del ingress de Azure la dirección de la conexión es la del propio ingress (todos compartirían cubo), por eso existe
 * {@code trustForwardedFor}: entonces se usa el valor que agrega el ingress, que es el de <b>más a la derecha</b>
 * ({@code trustedHops}-ésimo desde la derecha). Lo que el cliente haya puesto a la izquierda se ignora. Si la cabecera falta,
 * trae menos valores de los esperados o el valor no es una IP literal, se usa la dirección de la conexión.
 *
 * <p>La clave se normaliza para que no se pueda esquivar el límite cambiando de dirección dentro de la misma red: una IPv6 se
 * reduce a su prefijo /64 y una IPv6 "mapeada" de IPv4 se trata como la IPv4.
 */
public class ClientIpResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");
    private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]+");

    private final boolean trustForwardedFor;
    private final int trustedHops;

    public ClientIpResolver(boolean trustForwardedFor, int trustedHops) {
        this.trustForwardedFor = trustForwardedFor;
        this.trustedHops = trustedHops;
    }

    public String resolve(ServerHttpRequest request) {
        if (trustForwardedFor) {
            InetAddress forwarded = fromForwardedFor(request.getHeaders().get(FORWARDED_FOR));
            if (forwarded != null) {
                return normalize(forwarded);
            }
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "desconocida" : normalize(remote.getAddress());
    }

    private InetAddress fromForwardedFor(List<String> headerLines) {
        if (headerLines == null || headerLines.isEmpty()) {
            return null;
        }
        String[] hops = String.join(",", headerLines).split(",");
        if (hops.length < trustedHops) {
            return null;
        }
        return parseLiteral(hops[hops.length - trustedHops].trim());
    }

    /** Solo IP literal: jamás se resuelve un nombre (nada de DNS con un valor que controla el cliente). */
    static InetAddress parseLiteral(String value) {
        try {
            var v4 = IPV4.matcher(value);
            if (v4.matches()) {
                byte[] bytes = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int octet = Integer.parseInt(v4.group(i + 1));
                    if (octet > 255) {
                        return null;
                    }
                    bytes[i] = (byte) octet;
                }
                return InetAddress.getByAddress(bytes);
            }
            if (value.indexOf(':') >= 0 && IPV6_CHARS.matcher(value).matches()) {
                return InetAddress.getByName(value);   // con ':' Java lo trata como literal IPv6, sin DNS
            }
        } catch (UnknownHostException | NumberFormatException e) {
            return null;
        }
        return null;
    }

    static String normalize(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return address.getHostAddress();
        }
        // IPv6: prefijo /64 (una red doméstica o de empresa reparte un /64 entero a un cliente)
        return "%02x%02x:%02x%02x:%02x%02x:%02x%02x::/64".formatted(bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7]);
    }
}
