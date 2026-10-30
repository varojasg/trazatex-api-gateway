package com.nodotextil.trazatex.apigateway;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.awaitility.Awaitility.await;

/** Utilidades de las pruebas de revocación con Kafka real. Cada subclase declara su {@code @DynamicPropertySource} con su tópico. */
public abstract class RevocationItSupport extends GatewayTestSupport {

    protected static final UUID COMPANY = TestTokens.COMPANY_ID;

    protected void stubService() {
        ORG_ACCESS.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
    }

    protected static String tokenOf(UUID user) {
        return TestTokens.forUser(user, "OPERATOR", COMPANY);
    }

    protected WebTestClient.ResponseSpec call(String token) {
        return anonymous.get().uri("/api/license").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange();
    }

    protected int statusOf(String token) {
        return call(token).returnResult(String.class).getStatus().value();
    }

    protected int readinessStatus() {
        return anonymous.get().uri("/actuator/health/readiness").exchange().returnResult(String.class).getStatus().value();
    }

    protected void awaitReady(Duration atMost) {
        await().atMost(atMost).pollInterval(Duration.ofMillis(100)).until(() -> readinessStatus() == 200);
    }

    protected static List<String> messages(ListAppender<ILoggingEvent> logs, String topic) {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).filter(m -> m.contains(topic)).toList();
    }
}
