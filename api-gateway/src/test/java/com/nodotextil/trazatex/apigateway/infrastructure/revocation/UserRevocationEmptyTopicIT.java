package com.nodotextil.trazatex.apigateway.infrastructure.revocation;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nodotextil.trazatex.apigateway.KafkaTestSupport;
import com.nodotextil.trazatex.apigateway.RevocationEvents;
import com.nodotextil.trazatex.apigateway.RevocationItSupport;
import com.nodotextil.trazatex.apigateway.RevocationLogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** (a) Un tópico que existe pero está vacío (sistema recién instalado) deja al Gateway listo sin esperar ningún mensaje. */
@Import(RevocationLogCapture.class)
class UserRevocationEmptyTopicIT extends RevocationItSupport {

    @Autowired
    ListAppender<ILoggingEvent> logs;

    static final String TOPIC = KafkaTestSupport.uniqueTopic();

    static {
        KafkaTestSupport.createCompactedTopic(TOPIC, 1);
    }

    @DynamicPropertySource
    static void revocation(DynamicPropertyRegistry registry) {
        registry.add("trazatex.revocation.enabled", () -> "true");
        registry.add("trazatex.revocation.bootstrap-servers", KafkaTestSupport::bootstrap);
        registry.add("trazatex.revocation.topic", () -> TOPIC);
    }

    @Test
    void anEmptyTopicLeavesTheGatewayReadyRightAwayAndAuthenticatedRoutesWork() {
        stubService();

        awaitReady(Duration.ofSeconds(10));

        assertThat(statusOf(tokenOf(UUID.randomUUID()))).isEqualTo(200);
        assertThat(messages(logs, TOPIC)).filteredOn(m -> m.contains("Revocación lista")).singleElement().satisfies(m -> assertThat(m)
                .contains("0 usuarios INACTIVE cargados de 0 mensajes de " + TOPIC)
                .contains("leído hasta el offset {0=vacía}")
                .contains("fin del tópico al arrancar: {0=0}"));
    }

    @Test
    void andItKeepsWorkingLiveFromAnEmptyStart() {
        stubService();
        awaitReady(Duration.ofSeconds(10));
        UUID user = UUID.randomUUID();

        KafkaTestSupport.publish(TOPIC, user, RevocationEvents.userUpdated(user, "INACTIVE"));

        await().atMost(Duration.ofSeconds(5)).until(() -> statusOf(tokenOf(user)) == 401);
    }
}
