package com.nodotextil.trazatex.apigateway;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.kafka.KafkaContainer;
import com.nodotextil.trazatex.sharedtesting.TrazatexKafkaContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Un Kafka real (Testcontainers) para toda la JVM de pruebas; cada prueba usa su propio tópico. */
public final class KafkaTestSupport {

    private static final KafkaContainer KAFKA = new TrazatexKafkaContainer();

    static {
        KAFKA.start();
    }

    private KafkaTestSupport() {
    }

    public static String bootstrap() {
        return KAFKA.getBootstrapServers();
    }

    public static String uniqueTopic() {
        return "orgaccess.user." + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Como lo declara organization-access: {@code cleanup.policy=compact}. */
    public static void createCompactedTopic(String name, int partitions) {
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", bootstrap()))) {
            admin.createTopics(List.of(new NewTopic(name, partitions, (short) 1).configs(Map.of("cleanup.policy", "compact")))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static void publish(String topic, UUID key, String value) {
        publish(topic, key.toString(), value);
    }

    public static void publish(String topic, String key, String value) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.<String, Object>of(
                "bootstrap.servers", bootstrap(), "acks", "all",
                "key.serializer", StringSerializer.class.getName(), "value.serializer", StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
