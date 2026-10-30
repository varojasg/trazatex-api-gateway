package com.nodotextil.trazatex.apigateway.infrastructure.revocation;

import com.nodotextil.trazatex.apigateway.infrastructure.config.RevocationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration
@EnableConfigurationProperties(RevocationProperties.class)
class RevocationConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RevocationConfiguration.class);

    /** También es el {@code RevokedUsersPort} que usa la autenticación. Apagada (solo pruebas) queda "cargada" y vacía. */
    @Bean
    RevokedUsersRegistry revokedUsers(RevocationProperties props) {
        if (!props.enabled()) {
            log.warn("Revocación de usuarios inactivos APAGADA (trazatex.revocation.enabled=false): solo para pruebas; ningún token se revoca");
        }
        return new RevokedUsersRegistry(!props.enabled());
    }

    @Bean
    @ConditionalOnProperty(name = "trazatex.revocation.enabled", havingValue = "true", matchIfMissing = true)
    UserRevocationConsumer userRevocationConsumer(RevocationProperties props, RevokedUsersRegistry registry, JsonMapper json) {
        return new UserRevocationConsumer(props, UserRevocationConsumer.kafkaConsumers(props), registry, new UserAccountEventParser(json));
    }

    /** Su nombre de contributor es {@code revocation}; el grupo {@code readiness} lo incluye (application.yml). */
    @Bean
    RevocationHealthIndicator revocationHealthIndicator(RevokedUsersRegistry registry) {
        return new RevocationHealthIndicator(registry);
    }
}
