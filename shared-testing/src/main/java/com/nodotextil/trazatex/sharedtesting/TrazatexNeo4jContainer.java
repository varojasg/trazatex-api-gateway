package com.nodotextil.trazatex.sharedtesting;

import org.testcontainers.neo4j.Neo4jContainer;
import org.testcontainers.utility.DockerImageName;

public class TrazatexNeo4jContainer extends Neo4jContainer {

    /** Misma versión exacta que {@code docker-compose.yml}. */
    public static final String IMAGE = "neo4j:5.26.32-community";

    public TrazatexNeo4jContainer(String adminPassword) {
        super(DockerImageName.parse(IMAGE));
        withAdminPassword(adminPassword);
        withEnv("NEO4J_server_memory_heap_initial__size", "512m");
        withEnv("NEO4J_server_memory_heap_max__size", "512m");
        withEnv("NEO4J_server_memory_pagecache_size", "256m");
    }
}
