package com.nodotextil.trazatex.apigateway.infrastructure.revocation;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nodotextil.trazatex.apigateway.RevocationEvents;
import com.nodotextil.trazatex.apigateway.infrastructure.config.RevocationProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * El consumidor con un {@link MockConsumer}: se controla qué llega en cada {@code poll}, así que "no está listo hasta terminar de
 * leer", la reanudación tras un fallo y el apagado se prueban sin depender de tiempos de Kafka.
 */
class UserRevocationConsumerTest {

    private static final String TOPIC = "orgaccess.user";
    private static final TopicPartition P0 = new TopicPartition(TOPIC, 0);
    private static final TopicPartition P1 = new TopicPartition(TOPIC, 1);
    private static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID C = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    private final RevocationProperties props = new RevocationProperties(true, "no-se-usa:9092", TOPIC,
            Duration.ofMillis(5), Duration.ofMillis(20), Duration.ofMillis(100), Duration.ofSeconds(1));
    private final RevokedUsersRegistry registry = new RevokedUsersRegistry(false);
    private final Deque<MockConsumer<String, String>> sessions = new ArrayDeque<>();
    private final List<MockConsumer<String, String>> created = new CopyOnWriteArrayList<>();
    private UserRevocationConsumer consumer;
    private ListAppender<ILoggingEvent> logs;
    private final Logger consumerLogger = (Logger) LoggerFactory.getLogger(UserRevocationConsumer.class);

    @BeforeEach
    void setUp() {
        consumer = new UserRevocationConsumer(props, () -> {
            MockConsumer<String, String> next = sessions.poll();
            created.add(next);
            return next;
        }, registry, new UserAccountEventParser(JsonMapper.builder().build()));
        logs = new ListAppender<>();
        logs.start();
        consumerLogger.addAppender(logs);
        consumerLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        consumer.stop();
        consumerLogger.detachAppender(logs);
    }

    private MockConsumer<String, String> session(int partitions, Map<TopicPartition, Long> endOffsets) {
        MockConsumer<String, String> mock = new MockConsumer<>("earliest");
        List<PartitionInfo> infos = new ArrayList<>();
        for (int p = 0; p < partitions; p++) {
            infos.add(new PartitionInfo(TOPIC, p, null, null, null));
        }
        mock.updatePartitions(TOPIC, infos);
        mock.updateBeginningOffsets(Map.of(P0, 0L, P1, 0L));
        mock.updateEndOffsets(endOffsets);
        sessions.add(mock);
        return mock;
    }

    private static ConsumerRecord<String, String> record(TopicPartition tp, long offset, UUID user, String value) {
        return new ConsumerRecord<>(tp.topic(), tp.partition(), offset, user.toString(), value);
    }

    private List<String> logMessages(Level level) {
        return logs.list.stream().filter(e -> e.getLevel() == level).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static List<Thread> consumerThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals(UserRevocationConsumer.THREAD_NAME) && t.isAlive()).toList();
    }

    @Test
    void anEmptyTopicLeavesTheGatewayReadyRightAway() {
        session(2, Map.of(P0, 0L, P1, 0L));

        consumer.start();

        await().atMost(Duration.ofSeconds(2)).until(registry::isLoaded);
        assertThat(registry.inactiveCount()).isZero();
        assertThat(logMessages(Level.INFO)).anySatisfy(m -> assertThat(m).contains("0 usuarios INACTIVE cargados de 0 mensajes de orgaccess.user")
                .contains("{0=vacía, 1=vacía}").contains("{0=0, 1=0}"));
    }

    @Test
    void itIsNotLoadedUntilTheEndOfEveryPartitionIsReachedAndItLogsWhatItLoaded() {
        MockConsumer<String, String> mock = session(2, Map.of(P0, 4L, P1, 2L));
        List<String> loadedTooEarly = new CopyOnWriteArrayList<>();
        Runnable assertNotLoaded = () -> {
            if (registry.isLoaded()) {
                loadedTooEarly.add("cargado antes de tiempo");
            }
        };
        mock.schedulePollTask(() -> {
            assertNotLoaded.run();
            mock.addRecord(record(P0, 0, A, RevocationEvents.userUpdated(A, "INACTIVE")));
            mock.addRecord(record(P0, 1, B, RevocationEvents.userUpdated(B, "INACTIVE")));
        });
        mock.schedulePollTask(() -> {
            assertNotLoaded.run();
            mock.addRecord(record(P1, 0, C, RevocationEvents.userUpdated(C, "ACTIVE")));
        });
        mock.schedulePollTask(() -> {
            assertNotLoaded.run();
            mock.addRecord(record(P0, 2, B, RevocationEvents.userUpdated(B, "ACTIVE")));
            mock.addRecord(record(P0, 3, A, RevocationEvents.userUpdatedWithUnknownFields(A, "INACTIVE")));
        });
        mock.schedulePollTask(() -> {
            assertNotLoaded.run();   // P0 ya llegó a su fin, pero P1 todavía no
            mock.addRecord(record(P1, 1, C, RevocationEvents.userUpdated(C, "ACTIVE")));
        });

        consumer.start();

        await().atMost(Duration.ofSeconds(3)).until(registry::isLoaded);
        assertThat(loadedTooEarly).isEmpty();
        assertThat(registry.isRevoked(A)).isTrue();
        assertThat(registry.isRevoked(B)).as("INACTIVE y luego ACTIVE: el último manda").isFalse();
        assertThat(registry.isRevoked(C)).isFalse();
        assertThat(logMessages(Level.INFO)).singleElement().satisfies(m -> assertThat(m)
                .contains("1 usuarios INACTIVE cargados de 6 mensajes de orgaccess.user")
                .contains("leído hasta el offset {0=3, 1=1}")
                .contains("fin del tópico al arrancar: {0=4, 1=2}"));
    }

    @Test
    void unreadableMessagesAreSkippedLoggedWithoutTheirContentAndDoNotStopTheLoad() {
        MockConsumer<String, String> mock = session(1, Map.of(P0, 5L));
        mock.schedulePollTask(() -> {
            mock.addRecord(record(P0, 0, A, "{esto no es json"));
            mock.addRecord(record(P0, 1, B, "{\"payload\":{\"email\":\"secreto@hilos.pe\",\"status\":\"INACTIVE\"}}"));   // sin userId
            mock.addRecord(record(P0, 2, C, "{\"payload\":{\"userId\":\"" + C + "\"}}"));   // sin status
            mock.addRecord(record(P0, 3, A, RevocationEvents.userUpdated(A, "INACTIVE")));
            mock.addRecord(new ConsumerRecord<>(TOPIC, 0, 4, B.toString(), null));   // tumba
        });

        consumer.start();

        await().atMost(Duration.ofSeconds(2)).until(registry::isLoaded);
        assertThat(registry.isRevoked(A)).as("lo que viene después de los ilegibles se procesa").isTrue();
        assertThat(registry.isRevoked(B)).isFalse();
        assertThat(registry.isRevoked(C)).isFalse();
        assertThat(logMessages(Level.WARN)).hasSize(4).allSatisfy(m -> assertThat(m).contains("ilegible").doesNotContain("secreto@hilos.pe"));
        assertThat(logMessages(Level.INFO)).singleElement().satisfies(m -> assertThat(m).contains("1 usuarios INACTIVE").contains("4 ilegibles ignorados"));
    }

    @Test
    void unknownFieldsAreProcessedAndAnUnknownStatusRevokes() {
        MockConsumer<String, String> mock = session(1, Map.of(P0, 3L));
        mock.schedulePollTask(() -> {
            mock.addRecord(record(P0, 0, A, RevocationEvents.userUpdatedWithUnknownFields(A, "INACTIVE")));
            mock.addRecord(record(P0, 1, B, RevocationEvents.userUpdated(B, "SUSPENDED")));
            mock.addRecord(record(P0, 2, C, "{\"type\":\"OtroEvento\",\"payload\":{\"userId\":\"" + C + "\",\"status\":\"INACTIVE\"}}"));
        });

        consumer.start();

        await().atMost(Duration.ofSeconds(2)).until(registry::isLoaded);
        assertThat(registry.isRevoked(A)).isTrue();
        assertThat(registry.isRevoked(B)).isTrue();
        assertThat(registry.isRevoked(C)).as("otro tipo de evento no es de este consumidor").isFalse();
        assertThat(logMessages(Level.WARN)).singleElement().satisfies(m -> assertThat(m).contains("SUSPENDED").contains("no activo"));
    }

    @Test
    void ifTheTopicDoesNotExistYetItWaitsWithoutBeingReadyAndLoadsWhenItAppears() throws Exception {
        MockConsumer<String, String> mock = new MockConsumer<>("earliest");   // sin updatePartitions: el tópico no existe
        mock.updateBeginningOffsets(Map.of(P0, 0L));
        sessions.add(mock);

        consumer.start();
        Thread.sleep(300);

        assertThat(registry.isLoaded()).isFalse();
        assertThat(logMessages(Level.WARN)).anySatisfy(m -> assertThat(m).contains("aún no existe"));

        mock.updatePartitions(TOPIC, List.of(new PartitionInfo(TOPIC, 0, null, null, null)));
        mock.updateEndOffsets(Map.of(P0, 1L));
        mock.schedulePollTask(() -> mock.addRecord(record(P0, 0, A, RevocationEvents.userUpdated(A, "INACTIVE"))));

        await().atMost(Duration.ofSeconds(3)).until(registry::isLoaded);
        assertThat(registry.isRevoked(A)).isTrue();
    }

    @Test
    void afterAKafkaFailureItKeepsTheKnownSetStaysLoadedAndResumesWhereItWas() {
        MockConsumer<String, String> first = session(1, Map.of(P0, 3L));
        first.schedulePollTask(() -> {
            first.addRecord(record(P0, 0, A, RevocationEvents.userUpdated(A, "INACTIVE")));
            first.addRecord(record(P0, 1, B, RevocationEvents.userUpdated(B, "INACTIVE")));
            first.addRecord(record(P0, 2, A, RevocationEvents.userUpdated(A, "ACTIVE")));
        });
        // segunda conexión: el offset 0 trae un mensaje "envenenado" que NO debe volver a leerse (se retoma en el 3)
        UUID z = UUID.fromString("dddddddd-0000-0000-0000-000000000004");
        MockConsumer<String, String> second = session(1, Map.of(P0, 5L));
        second.schedulePollTask(() -> {
            second.addRecord(record(P0, 0, z, RevocationEvents.userUpdated(z, "INACTIVE")));
            second.addRecord(record(P0, 3, C, RevocationEvents.userUpdated(C, "INACTIVE")));
            second.addRecord(record(P0, 4, B, RevocationEvents.userUpdated(B, "ACTIVE")));
        });

        consumer.start();
        await().atMost(Duration.ofSeconds(2)).until(registry::isLoaded);
        assertThat(registry.isRevoked(B)).isTrue();

        first.schedulePollTask(() -> {
            throw new KafkaException("se cayó la conexión");
        });

        await().atMost(Duration.ofSeconds(3)).until(() -> registry.isRevoked(C));
        assertThat(registry.isLoaded()).isTrue();
        assertThat(registry.isRevoked(B)).as("lo nuevo se aplica").isFalse();
        assertThat(registry.isRevoked(z)).as("no se releyó desde el inicio").isFalse();
        assertThat(created).hasSize(2);
        assertThat(logMessages(Level.WARN)).anySatisfy(m -> assertThat(m).contains("Kafka no responde").contains("último conjunto conocido"));
    }

    @Test
    void stopEndsTheThreadAndClosesTheKafkaConsumer() {
        MockConsumer<String, String> mock = session(1, Map.of(P0, 0L));
        consumer.start();
        await().atMost(Duration.ofSeconds(2)).until(registry::isLoaded);
        assertThat(consumerThreads()).hasSize(1);

        consumer.stop();

        assertThat(consumer.isRunning()).isFalse();
        assertThat(consumerThreads()).isEmpty();
        assertThat(mock.closed()).isTrue();
        assertThat(logMessages(Level.INFO)).anySatisfy(m -> assertThat(m).contains("consumidor detenido"));
    }

    @Test
    void stopReturnsPromptlyEvenIfTheTopicNeverAppears() {
        MockConsumer<String, String> mock = new MockConsumer<>("earliest");
        sessions.add(mock);
        consumer.start();
        await().atMost(Duration.ofSeconds(2)).until(() -> !logMessages(Level.WARN).isEmpty());

        long started = System.nanoTime();
        consumer.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(consumerThreads()).isEmpty();
        assertThat(mock.closed()).isTrue();
    }

    @Test
    void startingTwiceDoesNotCreateASecondThreadAndStoppingTwiceIsHarmless() {
        session(1, Map.of(P0, 0L));

        consumer.start();
        consumer.start();
        await().atMost(Duration.ofSeconds(2)).until(registry::isLoaded);
        assertThat(consumerThreads()).hasSize(1);

        consumer.stop();
        consumer.stop();
        assertThat(consumerThreads()).isEmpty();
    }
}
