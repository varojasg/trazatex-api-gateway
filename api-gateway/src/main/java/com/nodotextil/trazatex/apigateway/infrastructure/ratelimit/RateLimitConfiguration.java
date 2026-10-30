package com.nodotextil.trazatex.apigateway.infrastructure.ratelimit;

import com.nodotextil.trazatex.apigateway.application.port.in.RateLimitUseCase;
import com.nodotextil.trazatex.apigateway.application.port.out.RateLimitPort;
import com.nodotextil.trazatex.apigateway.application.usecase.RateLimitService;
import com.nodotextil.trazatex.apigateway.domain.model.RateLimit;
import com.nodotextil.trazatex.apigateway.domain.model.RateLimitPolicy;
import com.nodotextil.trazatex.apigateway.infrastructure.config.RateLimitProperties;
import com.nodotextil.trazatex.apigateway.infrastructure.security.AuthFailureThrottle;
import com.nodotextil.trazatex.apigateway.infrastructure.security.PublicRoutes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfiguration.class);

    @Bean
    RateLimitPolicy rateLimitPolicy(RateLimitProperties p) {
        return new RateLimitPolicy(rule(p.login()), rule(p.invitation()), rule(p.user()), rule(p.company()), rule(p.authFailure()));
    }

    @Bean
    RateLimitPort rateLimitPort(RateLimitProperties p) {
        return new Bucket4jRateLimitAdapter(p.idleExpiry(), p.maxEntries());
    }

    @Bean
    RateLimitUseCase rateLimitUseCase(RateLimitPort port, RateLimitPolicy policy) {
        return new RateLimitService(port, policy);
    }

    @Bean
    ClientIpResolver clientIpResolver(RateLimitProperties p) {
        log.info("Rate limiting: login {}/{} por IP, invitaciones {}/{} por IP, rutas autenticadas {}/{} por usuario y {}/{} por empresa, "
                        + "peticiones sin credencial válida {}/{} por IP; cubos inactivos expiran a los {} (máx. {} por ámbito). {}",
                p.login().permits(), p.login().window(), p.invitation().permits(), p.invitation().window(),
                p.user().permits(), p.user().window(), p.company().permits(), p.company().window(),
                p.authFailure().permits(), p.authFailure().window(), p.idleExpiry(), p.maxEntries(),
                p.trustForwardedFor()
                        ? "IP del cliente: valor %d desde la derecha de X-Forwarded-For (lo agrega el ingress); si falta, la dirección de la conexión".formatted(p.trustedHops())
                        : "IP del cliente: dirección de la conexión (X-Forwarded-For NO se usa)");
        return new ClientIpResolver(p.trustForwardedFor(), p.trustedHops());
    }

    @Bean
    AuthFailureThrottle authFailureThrottle(RateLimitUseCase useCase, ClientIpResolver clientIps) {
        return new RateLimitAuthFailureThrottle(useCase, clientIps);
    }

    @Bean
    RateLimitGatewayFilter rateLimitGatewayFilter(RateLimitUseCase useCase, PublicRoutes publicRoutes, ClientIpResolver clientIps) {
        return new RateLimitGatewayFilter(useCase, publicRoutes, clientIps);
    }

    private static RateLimit rule(RateLimitProperties.Rule rule) {
        return new RateLimit(rule.permits(), rule.window());
    }
}
