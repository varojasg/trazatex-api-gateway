package com.nodotextil.trazatex.apigateway.infrastructure.ratelimit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** La IP de las rutas públicas: la de la conexión, salvo que se confíe explícitamente en el valor que agrega el ingress. */
class ClientIpResolverTest {

    private static final String CONNECTION = "203.0.113.9";

    private static MockServerHttpRequest request(String... forwardedForLines) throws Exception {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new InetSocketAddress(InetAddress.getByName(CONNECTION), 54321));
        for (String line : forwardedForLines) {
            builder.header("X-Forwarded-For", line);
        }
        return builder.build();
    }

    private static MockServerHttpRequest requestFrom(String address, String... forwardedForLines) throws Exception {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.post("/api/auth/login")
                .remoteAddress(new InetSocketAddress(InetAddress.getByName(address), 54321));
        for (String line : forwardedForLines) {
            builder.header("X-Forwarded-For", line);
        }
        return builder.build();
    }

    @Test
    void byDefaultTheIpIsTheConnectionAndAForgedForwardedForIsNeverUsed() throws Exception {
        ClientIpResolver resolver = new ClientIpResolver(false, 1);

        assertThat(resolver.resolve(request())).isEqualTo(CONNECTION);
        assertThat(resolver.resolve(request("1.2.3.4"))).isEqualTo(CONNECTION);
        assertThat(resolver.resolve(request("1.2.3.4, 5.6.7.8"))).isEqualTo(CONNECTION);
        assertThat(resolver.resolve(request("::1", "9.9.9.9"))).isEqualTo(CONNECTION);
        assertThat(resolver.resolve(request("evil.example.com"))).isEqualTo(CONNECTION);
    }

    @Test
    void manyDifferentForgedValuesAllFallInTheSameBucket() throws Exception {
        ClientIpResolver resolver = new ClientIpResolver(false, 1);

        for (int i = 0; i < 100; i++) {
            assertThat(resolver.resolve(request("10.0." + i + ".1"))).isEqualTo(CONNECTION);
        }
    }

    @Test
    void whenTheIngressIsTrustedTheRightmostValueWinsAndWhatTheClientPutOnTheLeftIsIgnored() throws Exception {
        ClientIpResolver resolver = new ClientIpResolver(true, 1);

        assertThat(resolver.resolve(request("198.51.100.7"))).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(request("6.6.6.6, 198.51.100.7"))).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(request("6.6.6.6, 7.7.7.7, 198.51.100.7"))).isEqualTo("198.51.100.7");
        assertThat(resolver.resolve(request("  6.6.6.6 ,  198.51.100.7  "))).as("espacios").isEqualTo("198.51.100.7");
    }

    @Test
    void severalHeaderLinesAreJoinedInOrder() throws Exception {
        ClientIpResolver resolver = new ClientIpResolver(true, 1);

        assertThat(resolver.resolve(request("6.6.6.6", "198.51.100.7"))).isEqualTo("198.51.100.7");
    }

    @Test
    void withMoreTrustedHopsTheNthFromTheRightIsUsed() throws Exception {
        ClientIpResolver twoHops = new ClientIpResolver(true, 2);

        assertThat(twoHops.resolve(request("6.6.6.6, 198.51.100.7, 10.1.1.1"))).isEqualTo("198.51.100.7");
        assertThat(twoHops.resolve(request("198.51.100.7, 10.1.1.1"))).isEqualTo("198.51.100.7");
        assertThat(twoHops.resolve(request("10.1.1.1"))).as("menos valores de los esperados: se usa la conexión").isEqualTo(CONNECTION);
    }

    @Test
    void ifTheHeaderIsMissingOrUnusableTheConnectionIsUsedEvenWhenTheIngressIsTrusted() throws Exception {
        ClientIpResolver resolver = new ClientIpResolver(true, 1);

        assertThat(resolver.resolve(request())).isEqualTo(CONNECTION);
        assertThat(resolver.resolve(request(""))).isEqualTo(CONNECTION);
        assertThat(resolver.resolve(request("1.2.3.4, "))).isEqualTo(CONNECTION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"evil.example.com", "localhost", "999.1.1.1", "1.2.3", "1.2.3.4.5", "1.2.3.4:8080", "[::1]:80", "unknown", "-1.2.3.4",
            "1.2.3.4/32", "gggg::1", "::1::2", "1.2.3.4 5.6.7.8", "'; DROP TABLE x;--", "0x7f.0.0.1"})
    void aValueThatIsNotALiteralIpIsNeverResolvedAndTheConnectionIsUsed(String forged) throws Exception {
        ClientIpResolver resolver = new ClientIpResolver(true, 1);

        assertThat(resolver.resolve(request("1.2.3.4, " + forged))).isEqualTo(CONNECTION);
    }

    @Test
    void anIpv6AddressIsReducedToItsSlash64() throws Exception {
        ClientIpResolver resolver = new ClientIpResolver(false, 1);

        String a = resolver.resolve(requestFrom("2001:db8:aaaa:1::1"));
        String b = resolver.resolve(requestFrom("2001:db8:aaaa:1:ffff:ffff:ffff:ffff"));
        String otherNetwork = resolver.resolve(requestFrom("2001:db8:aaaa:2::1"));

        assertThat(a).isEqualTo(b).isEqualTo("2001:0db8:aaaa:0001::/64");
        assertThat(otherNetwork).isNotEqualTo(a);
    }

    @Test
    void anIpv4MappedIpv6AddressCountsAsTheIpv4() throws Exception {
        ClientIpResolver resolver = new ClientIpResolver(true, 1);

        assertThat(resolver.resolve(request("::ffff:198.51.100.7"))).isEqualTo("198.51.100.7");
    }

    @Test
    void theIpv6ValueTheIngressAddsIsAlsoReducedToSlash64() throws Exception {
        ClientIpResolver resolver = new ClientIpResolver(true, 1);

        assertThat(resolver.resolve(request("6.6.6.6, 2001:db8:1:2:3:4:5:6"))).isEqualTo("2001:0db8:0001:0002::/64");
    }

    @Test
    void aRequestWithoutRemoteAddressDoesNotBreakAndSharesOneKey() {
        MockServerHttpRequest noAddress = MockServerHttpRequest.post("/api/auth/login").build();

        assertThat(new ClientIpResolver(false, 1).resolve(noAddress)).isEqualTo("desconocida");
    }

    @Test
    void theResolverNeverTouchesTheNetwork() {
        // Un nombre con aspecto de host no puede provocar una consulta DNS: se descarta como literal inválido
        assertThat(ClientIpResolver.parseLiteral("host-" + UUID.randomUUID() + ".invalid")).isNull();
    }
}
