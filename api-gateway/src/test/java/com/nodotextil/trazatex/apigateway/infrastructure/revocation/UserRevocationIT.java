package com.nodotextil.trazatex.apigateway.infrastructure.revocation;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nodotextil.trazatex.apigateway.KafkaTestSupport;
import com.nodotextil.trazatex.apigateway.RevocationEvents;
import com.nodotextil.trazatex.apigateway.RevocationItSupport;
import com.nodotextil.trazatex.apigateway.RevocationLogCapture;
import com.nodotextil.trazatex.apigateway.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Import(RevocationLogCapture.class)
class UserRevocationIT extends RevocationItSupport {

    @Autowired
    ListAppender<ILoggingEvent> logs;

    static final String TOPIC = KafkaTestSupport.uniqueTopic();

    static final UUID A_INACTIVE = UUID.randomUUID();
    static final UUID B_INACTIVE_THEN_ACTIVE = UUID.randomUUID();
    static final UUID C_ACTIVE = UUID.randomUUID();
    static final UUID D_INACTIVE_UNKNOWN_FIELDS = UUID.randomUUID();
    static final UUID E_NO_STATUS = UUID.randomUUID();
    static final UUID F_GARBAGE = UUID.randomUUID();
    static final UUID Z_NO_USER_ID = UUID.randomUUID();

    static {
        // 2 particiones; el estado previo al arranque del Gateway: 8 mensajes (3 ilegibles), 2 usuarios INACTIVE
        KafkaTestSupport.createCompactedTopic(TOPIC, 2);
        KafkaTestSupport.publish(TOPIC, A_INACTIVE, RevocationEvents.userUpdated(A_INACTIVE, "INACTIVE"));
        KafkaTestSupport.publish(TOPIC, B_INACTIVE_THEN_ACTIVE, RevocationEvents.userUpdated(B_INACTIVE_THEN_ACTIVE, "INACTIVE"));
        KafkaTestSupport.publish(TOPIC, B_INACTIVE_THEN_ACTIVE, RevocationEvents.userUpdated(B_INACTIVE_THEN_ACTIVE, "ACTIVE"));
        KafkaTestSupport.publish(TOPIC, C_ACTIVE, RevocationEvents.userUpdated(C_ACTIVE, "ACTIVE"));
        KafkaTestSupport.publish(TOPIC, D_INACTIVE_UNKNOWN_FIELDS, RevocationEvents.userUpdatedWithUnknownFields(D_INACTIVE_UNKNOWN_FIELDS, "INACTIVE"));
        KafkaTestSupport.publish(TOPIC, E_NO_STATUS, "{\"payload\":{\"userId\":\"" + E_NO_STATUS + "\",\"email\":\"e@hilos.pe\"}}");
        KafkaTestSupport.publish(TOPIC, F_GARBAGE, "esto no es json");
        KafkaTestSupport.publish(TOPIC, Z_NO_USER_ID, "{\"payload\":{\"status\":\"INACTIVE\"}}");
    }

    @DynamicPropertySource
    static void revocation(DynamicPropertyRegistry registry) {
        registry.add("trazatex.revocation.enabled", () -> "true");
        registry.add("trazatex.revocation.bootstrap-servers", KafkaTestSupport::bootstrap);
        registry.add("trazatex.revocation.topic", () -> TOPIC);
    }

    @BeforeEach
    void ready() {
        stubService();
        awaitReady(Duration.ofSeconds(30));
    }

    @Test
    void whenItBecomesReadyItHasAlreadyReadTheWholeTopicAndLogsWhatItLoaded() {
        // Recién listo: lo último que se publicó antes de arrancar ya está aplicado (nunca 200 para un revocado)
        assertThat(statusOf(tokenOf(A_INACTIVE))).isEqualTo(401);
        assertThat(statusOf(tokenOf(D_INACTIVE_UNKNOWN_FIELDS))).isEqualTo(401);

        assertThat(messages(logs, TOPIC)).filteredOn(m -> m.contains("Revocación lista")).singleElement().satisfies(m -> assertThat(m)
                .contains("2 usuarios INACTIVE cargados de 8 mensajes de " + TOPIC)
                .contains("3 ilegibles ignorados")
                .containsPattern("leído hasta el offset \\{0=\\d+, 1=\\d+\\}")
                .containsPattern("fin del tópico al arrancar: \\{0=\\d+, 1=\\d+\\}"));
    }

    @Test
    void theRevokedTokensGetA401WithTheirOwnCodeAndTheRestStillWork() {
        call(tokenOf(A_INACTIVE)).expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody().jsonPath("$.code").isEqualTo("USER_INACTIVE").jsonPath("$.message").isNotEmpty();

        assertThat(statusOf(tokenOf(B_INACTIVE_THEN_ACTIVE))).as("INACTIVE y luego ACTIVE: vale el último").isEqualTo(200);
        assertThat(statusOf(tokenOf(C_ACTIVE))).isEqualTo(200);
        assertThat(statusOf(tokenOf(E_NO_STATUS))).as("mensaje ilegible: se ignora, no se revoca").isEqualTo(200);
        assertThat(statusOf(tokenOf(F_GARBAGE))).isEqualTo(200);
        assertThat(statusOf(tokenOf(Z_NO_USER_ID))).isEqualTo(200);
        assertThat(statusOf(tokenOf(UUID.randomUUID()))).as("un usuario que el tópico no conoce").isEqualTo(200);
    }

    @Test
    void anInactiveUserCanStillReachThePublicRoutesToLogInAgain() {
        anonymous.post().uri("/api/auth/login").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenOf(A_INACTIVE))
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();
    }

    @Test
    void inactivatingAUserStopsItsTokenWithinSecondsAndReactivatingAcceptsTheSameTokenAgain() {
        UUID user = UUID.randomUUID();
        String token = tokenOf(user);
        assertThat(statusOf(token)).isEqualTo(200);

        long published = System.nanoTime();
        KafkaTestSupport.publish(TOPIC, user, RevocationEvents.userUpdated(user, "INACTIVE"));
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).until(() -> statusOf(token) == 401);
        Duration toRevoke = Duration.ofNanos(System.nanoTime() - published);
        assertThat(toRevoke).as("tiempo hasta que el token dejó de servir").isLessThan(Duration.ofSeconds(5));
        assertThat(statusOf(token)).isEqualTo(401);

        // (b) el mismo token, todavía vigente, vuelve a servir al reactivar
        KafkaTestSupport.publish(TOPIC, user, RevocationEvents.userUpdated(user, "ACTIVE"));
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).until(() -> statusOf(token) == 200);

        // y se puede volver a inactivar
        KafkaTestSupport.publish(TOPIC, user, RevocationEvents.userUpdated(user, "INACTIVE"));
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50)).until(() -> statusOf(token) == 401);
    }

    @Test
    void theOtherUsersAreNotAffectedWhenOneIsInactivated() {
        UUID victim = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        KafkaTestSupport.publish(TOPIC, victim, RevocationEvents.userUpdated(victim, "INACTIVE"));
        await().atMost(Duration.ofSeconds(5)).until(() -> statusOf(tokenOf(victim)) == 401);

        assertThat(statusOf(tokenOf(bystander))).isEqualTo(200);
        assertThat(statusOf(TestTokens.operator())).isEqualTo(200);
    }

    @Test
    void aBrokenMessageWhileRunningDoesNotStopTheConsumer() {
        UUID before = UUID.randomUUID();
        UUID after = UUID.randomUUID();
        KafkaTestSupport.publish(TOPIC, before, RevocationEvents.userUpdated(before, "INACTIVE"));
        KafkaTestSupport.publish(TOPIC, UUID.randomUUID(), "{\"payload\":{\"userId\":\"" + UUID.randomUUID() + "\"}}");
        KafkaTestSupport.publish(TOPIC, UUID.randomUUID(), "basura \u0000 {");
        KafkaTestSupport.publish(TOPIC, after, RevocationEvents.userUpdatedWithUnknownFields(after, "INACTIVE"));

        await().atMost(Duration.ofSeconds(5)).until(() -> statusOf(tokenOf(after)) == 401);
        assertThat(statusOf(tokenOf(before))).isEqualTo(401);
        assertThat(readinessStatus()).isEqualTo(200);
    }
}
