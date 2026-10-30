package com.nodotextil.trazatex.sharedtesting;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** El Neo4j de las pruebas se comporta como el del compose: misma imagen y misma memoria. Requiere Docker. */
class TrazatexNeo4jContainerTest {

    static final TrazatexNeo4jContainer NEO4J = new TrazatexNeo4jContainer("prueba-de-memoria");

    @BeforeAll
    static void start() {
        NEO4J.start();
    }

    @AfterAll
    static void stop() {
        NEO4J.stop();
    }

    private static Map<String, String> settings() {
        Map<String, String> values = new HashMap<>();
        try (Driver driver = GraphDatabase.driver(NEO4J.getBoltUrl(), AuthTokens.basic("neo4j", "prueba-de-memoria")); Session session = driver.session()) {
            session.run("SHOW SETTINGS YIELD name, value WHERE name STARTS WITH 'server.memory.heap' OR name = 'server.memory.pagecache.size' RETURN name, value")
                    .forEachRemaining(record -> values.put(record.get("name").asString(), record.get("value").asString()));
        }
        return values;
    }

    @Test
    void theServerRunsWithTheMemoryOfTheComposeFile() {
        Map<String, String> settings = settings();

        assertThat(settings).containsEntry("server.memory.heap.initial_size", "512.00MiB").containsEntry("server.memory.heap.max_size", "512.00MiB")
                .containsEntry("server.memory.pagecache.size", "256.00MiB");
    }

    @Test
    void theImageAndTheMemoryAreExactlyTheOnesOfTheComposeFile() throws Exception {
        String compose = Files.readString(Path.of("..", "docker-compose.yml"));

        assertThat(compose).contains("image: " + TrazatexNeo4jContainer.IMAGE);
        assertThat(compose).contains("NEO4J_server_memory_heap_initial__size: 512m").contains("NEO4J_server_memory_heap_max__size: 512m")
                .contains("NEO4J_server_memory_pagecache_size: 256m");
    }
}
