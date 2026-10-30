package com.nodotextil.trazatex.sharedtesting;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El Kafka de las pruebas se comporta como el del compose: no crea tópicos solos. Requiere Docker. */
class TrazatexKafkaContainerTest {

    static final TrazatexKafkaContainer KAFKA = new TrazatexKafkaContainer();

    @BeforeAll
    static void start() {
        KAFKA.start();
    }

    @AfterAll
    static void stop() {
        KAFKA.stop();
    }

    private static Map<String, Object> clientConfig() {
        return Map.of("bootstrap.servers", KAFKA.getBootstrapServers());
    }

    @Test
    void theBrokerReportsAutoCreateTopicsAsDisabled() throws Exception {
        try (AdminClient admin = AdminClient.create(clientConfig())) {
            ConfigResource broker = new ConfigResource(ConfigResource.Type.BROKER, "1");

            String value = admin.describeConfigs(List.of(broker)).all().get(10, TimeUnit.SECONDS).get(broker).get("auto.create.topics.enable").value();

            assertThat(value).isEqualTo("false");
        }
    }

    @Test
    void publishingToATopicNobodyDeclaredFailsAndTheTopicIsNotCreated() throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.<String, Object>of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000, ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            assertThatThrownBy(() -> producer.send(new ProducerRecord<>("nadie.lo.declaro", "k", "v")).get(10, TimeUnit.SECONDS))
                    .satisfiesAnyOf(e -> assertThat(e).isInstanceOf(TimeoutException.class),
                            e -> assertThat(e).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(TimeoutException.class));
        }
        try (AdminClient admin = AdminClient.create(clientConfig())) {
            assertThat(admin.listTopics().names().get(10, TimeUnit.SECONDS)).doesNotContain("nadie.lo.declaro");
        }
    }

    @Test
    void aDeclaredTopicAcceptsMessages() throws Exception {
        try (AdminClient admin = AdminClient.create(clientConfig())) {
            admin.createTopics(List.of(new NewTopic("declarado", 1, (short) 1))).all().get(10, TimeUnit.SECONDS);
        }
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.<String, Object>of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class, ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            assertThat(producer.send(new ProducerRecord<>("declarado", "k", "v")).get(10, TimeUnit.SECONDS).topic()).isEqualTo("declarado");
        }
    }

    @Test
    void theImageIsTheSameExactVersionAsTheComposeFile() throws Exception {
        String compose = Files.readString(Path.of("..", "docker-compose.yml"));

        assertThat(compose).contains("image: " + TrazatexKafkaContainer.IMAGE);
        assertThat(compose).contains("KAFKA_AUTO_CREATE_TOPICS_ENABLE: \"false\"");
    }
}
