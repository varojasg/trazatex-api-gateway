package com.nodotextil.trazatex.apigateway.application.usecase;

import com.nodotextil.trazatex.apigateway.application.dto.RateLimitSubject;
import com.nodotextil.trazatex.apigateway.application.port.out.RateLimitPort;
import com.nodotextil.trazatex.apigateway.domain.model.AuthenticatedUser;
import com.nodotextil.trazatex.apigateway.domain.model.RateLimit;
import com.nodotextil.trazatex.apigateway.domain.model.RateLimitDecision;
import com.nodotextil.trazatex.apigateway.domain.model.RateLimitPolicy;
import com.nodotextil.trazatex.apigateway.domain.model.RateLimitScope;
import com.nodotextil.trazatex.apigateway.domain.model.Role;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** La lógica de qué cubos aplican y cómo se combinan, con un puerto de mentira que registra lo que se le pide. */
class RateLimitServiceTest {

    private static final RateLimit LOGIN = new RateLimit(60, Duration.ofMinutes(1));
    private static final RateLimit INVITATION = new RateLimit(30, Duration.ofMinutes(1));
    private static final RateLimit USER = new RateLimit(120, Duration.ofMinutes(1));
    private static final RateLimit COMPANY = new RateLimit(600, Duration.ofMinutes(1));
    private static final RateLimit AUTH_FAILURE = new RateLimit(300, Duration.ofMinutes(1));

    private final List<String> calls = new ArrayList<>();
    private final Set<RateLimitScope> exhausted = new HashSet<>();
    private final RateLimitPort port = new RateLimitPort() {
        @Override
        public RateLimitDecision tryConsume(RateLimitScope scope, String key, RateLimit limit) {
            calls.add("consume " + scope + " " + key + " " + limit.permits());
            return exhausted.contains(scope) ? RateLimitDecision.rejected(Duration.ofSeconds(7 + scope.ordinal())) : RateLimitDecision.ALLOWED;
        }

        @Override
        public void refund(RateLimitScope scope, String key, RateLimit limit) {
            calls.add("refund " + scope + " " + key);
        }
    };
    private final RateLimitService service = new RateLimitService(port, new RateLimitPolicy(LOGIN, INVITATION, USER, COMPANY, AUTH_FAILURE));

    private final UUID userId = UUID.randomUUID();
    private final UUID companyId = UUID.randomUUID();
    private final AuthenticatedUser operator = new AuthenticatedUser(userId, Role.OPERATOR, companyId);
    private final AuthenticatedUser owner = new AuthenticatedUser(userId, Role.LICENSE_OWNER, null);

    @Test
    void loginAndInvitationsUseTheirOwnBucketByIp() {
        assertThat(service.check(new RateLimitSubject.Login("10.0.0.1")).allowed()).isTrue();
        assertThat(service.check(new RateLimitSubject.Invitation("10.0.0.1")).allowed()).isTrue();

        assertThat(calls).containsExactly("consume LOGIN_IP 10.0.0.1 60", "consume INVITATION_IP 10.0.0.1 30");
    }

    @Test
    void anAuthenticationFailureUsesItsOwnBucketByIp() {
        assertThat(service.check(new RateLimitSubject.AuthenticationFailure("10.0.0.1")).allowed()).isTrue();
        exhausted.add(RateLimitScope.AUTH_FAILURE_IP);
        assertThat(service.check(new RateLimitSubject.AuthenticationFailure("10.0.0.1")).allowed()).isFalse();

        assertThat(calls).containsExactly("consume AUTH_FAILURE_IP 10.0.0.1 300", "consume AUTH_FAILURE_IP 10.0.0.1 300");
    }

    @Test
    void anAuthenticatedRequestSpendsOnePermitFromTheUserAndOneFromTheCompany() {
        assertThat(service.check(new RateLimitSubject.Authenticated(operator)).allowed()).isTrue();

        assertThat(calls).containsExactly("consume USER " + userId + " 120", "consume COMPANY " + companyId + " 600");
    }

    @Test
    void theOwnerHasNoCompanyAndOnlyUsesTheUserBucket() {
        assertThat(service.check(new RateLimitSubject.Authenticated(owner)).allowed()).isTrue();

        assertThat(calls).containsExactly("consume USER " + userId + " 120");
    }

    @Test
    void ifTheUserBucketIsExhaustedTheCompanyDoesNotSpendAnything() {
        exhausted.add(RateLimitScope.USER);

        RateLimitDecision decision = service.check(new RateLimitSubject.Authenticated(operator));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfter()).isEqualTo(Duration.ofSeconds(7 + RateLimitScope.USER.ordinal()));
        assertThat(calls).containsExactly("consume USER " + userId + " 120");
    }

    @Test
    void ifTheCompanyBucketIsExhaustedTheRequestIsRejectedAndTheUserGetsItsPermitBack() {
        exhausted.add(RateLimitScope.COMPANY);

        RateLimitDecision decision = service.check(new RateLimitSubject.Authenticated(operator));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfter()).as("el motivo del rechazo es el de la empresa").isEqualTo(Duration.ofSeconds(7 + RateLimitScope.COMPANY.ordinal()));
        assertThat(calls).containsExactly("consume USER " + userId + " 120", "consume COMPANY " + companyId + " 600", "refund USER " + userId);
    }

    @Test
    void bothBucketsExhaustedIsRejectedByTheUserFirst() {
        exhausted.add(RateLimitScope.USER);
        exhausted.add(RateLimitScope.COMPANY);

        assertThat(service.check(new RateLimitSubject.Authenticated(operator)).allowed()).isFalse();
        assertThat(calls).hasSize(1);
    }

    @Test
    void anExhaustedLoginBucketRejects() {
        exhausted.add(RateLimitScope.LOGIN_IP);

        RateLimitDecision decision = service.check(new RateLimitSubject.Login("10.0.0.1"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfter()).isPositive();
    }
}
