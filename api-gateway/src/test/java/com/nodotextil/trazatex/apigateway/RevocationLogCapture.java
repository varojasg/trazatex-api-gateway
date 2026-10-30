package com.nodotextil.trazatex.apigateway;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Captura el log del consumidor de revocación. Se adjunta al crear el contexto: Spring Boot reinicia Logback al cargarlo y
 * borraría un appender puesto antes. Se crea antes de que el consumidor arranque, así que ve el mensaje de "Revocación lista".
 */
@TestConfiguration
public class RevocationLogCapture {

    private static final String LOGGER = "com.nodotextil.trazatex.apigateway.infrastructure.revocation.UserRevocationConsumer";

    @Bean(destroyMethod = "stop")
    ListAppender<ILoggingEvent> revocationLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(LOGGER)).addAppender(appender);
        return appender;
    }
}
