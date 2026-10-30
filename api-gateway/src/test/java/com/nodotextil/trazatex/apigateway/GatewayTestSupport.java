package com.nodotextil.trazatex.apigateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Base de las pruebas con el Gateway levantado: organization-access y su JWKS son dos WireMock que viven toda la JVM (los
 * contextos de Spring se reutilizan entre clases, así que sus puertos no pueden cambiar). {@link #client} ya envía un token
 * válido de operario; {@link #anonymous} no envía ninguno.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "trazatex.gateway.services.organization-access-url=http://localhost:${test.org-access.port}",
        "trazatex.gateway.services.production-url=http://localhost:${test.production.port}",
        "trazatex.gateway.services.traceability-url=http://localhost:${test.traceability.port}",
        "trazatex.jwt.jwks-uri=http://localhost:${test.jwks.port}" + GatewayTestSupport.JWKS_PATH,
        // Sin Kafka salvo en las pruebas de revocación, que lo encienden con @DynamicPropertySource
        "trazatex.revocation.enabled=false",
        // Límites muy altos: estas suites hacen cientos de peticiones con el mismo usuario e IP. Las pruebas de rate limiting
        // los sobrescriben con los suyos
        "trazatex.rate-limit.login.permits=1000000", "trazatex.rate-limit.invitation.permits=1000000",
        "trazatex.rate-limit.user.permits=1000000", "trazatex.rate-limit.company.permits=1000000",
        "trazatex.rate-limit.auth-failure.permits=1000000"})
public abstract class GatewayTestSupport {

    public static final String JWKS_PATH = "/.well-known/jwks.json";

    protected static final WireMockServer ORG_ACCESS = start();
    protected static final WireMockServer PRODUCTION = start();
    protected static final WireMockServer TRACEABILITY = start();
    protected static final WireMockServer JWKS = start();

    static {
        // Los puertos entran por propiedades inline: una subclase puede sobrescribirlas con su propio @TestPropertySource
        System.setProperty("test.org-access.port", String.valueOf(ORG_ACCESS.port()));
        System.setProperty("test.production.port", String.valueOf(PRODUCTION.port()));
        System.setProperty("test.traceability.port", String.valueOf(TRACEABILITY.port()));
        System.setProperty("test.jwks.port", String.valueOf(JWKS.port()));
    }

    private static WireMockServer start() {
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        return server;
    }

    @Autowired
    protected WebTestClient anonymous;

    protected WebTestClient client;

    @BeforeEach
    void resetBackends() {
        ORG_ACCESS.resetAll();
        PRODUCTION.resetAll();
        TRACEABILITY.resetAll();
        JWKS.resetAll();
        JWKS.stubFor(get(urlEqualTo(JWKS_PATH)).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withHeader("Cache-Control", "public, max-age=300").withBody(TestTokens.jwks(TestTokens.KEY))));
        client = anonymous.mutate().defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.operator()).build();
    }
}
