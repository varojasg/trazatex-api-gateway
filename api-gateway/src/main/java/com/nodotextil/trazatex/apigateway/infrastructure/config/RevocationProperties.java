package com.nodotextil.trazatex.apigateway.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("trazatex.revocation")
public record RevocationProperties(
        Boolean enabled,
        String bootstrapServers,
        String topic,
        Duration pollTimeout,
        Duration retryInitialBackoff,
        Duration retryMaxBackoff,
        Duration apiTimeout) {

    public RevocationProperties {
        enabled = enabled == null || enabled;
        if (enabled && (bootstrapServers == null || bootstrapServers.isBlank())) {
            throw new IllegalStateException("Falta KAFKA_BOOTSTRAP_SERVERS (trazatex.revocation.bootstrap-servers)");
        }
        topic = topic == null || topic.isBlank() ? "orgaccess.user" : topic;
        pollTimeout = pollTimeout == null ? Duration.ofMillis(200) : pollTimeout;
        retryInitialBackoff = retryInitialBackoff == null ? Duration.ofSeconds(1) : retryInitialBackoff;
        retryMaxBackoff = retryMaxBackoff == null ? Duration.ofSeconds(30) : retryMaxBackoff;
        apiTimeout = apiTimeout == null ? Duration.ofSeconds(10) : apiTimeout;
    }
}
