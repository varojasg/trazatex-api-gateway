package com.nodotextil.trazatex.apigateway.infrastructure.ratelimit;

import com.nodotextil.trazatex.apigateway.application.dto.RateLimitSubject;
import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationFailedException;
import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationFailedException.Reason;
import com.nodotextil.trazatex.apigateway.domain.model.RateLimitDecision;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitAuthFailureThrottleTest {

    private final List<RateLimitSubject> checked = new ArrayList<>();
    private boolean exhausted;
    private final RateLimitAuthFailureThrottle throttle = new RateLimitAuthFailureThrottle(subject -> {
        checked.add(subject);
        return exhausted ? RateLimitDecision.rejected(Duration.ofSeconds(9)) : RateLimitDecision.ALLOWED;
    }, new ClientIpResolver(false, 1));

    private MockServerHttpRequest request() throws Exception {
        return MockServerHttpRequest.get("/api/companies").remoteAddress(new InetSocketAddress(InetAddress.getByName("203.0.113.5"), 4000)).build();
    }

    @Test
    void onlyRequestsWithoutAnyGenuineCredentialSpendTheBucketOfTheirIp() throws Exception {
        assertThat(throttle.onFailure(request(), new AuthenticationFailedException(Reason.MISSING_TOKEN))).isEmpty();
        assertThat(throttle.onFailure(request(), new AuthenticationFailedException(Reason.INVALID_TOKEN))).isEmpty();
        assertThat(throttle.onFailure(request(), new AuthenticationFailedException(Reason.TOKEN_EXPIRED))).isEmpty();
        assertThat(throttle.onFailure(request(), new AuthenticationFailedException(Reason.USER_INACTIVE))).isEmpty();

        assertThat(checked).containsExactly(new RateLimitSubject.AuthenticationFailure("203.0.113.5"), new RateLimitSubject.AuthenticationFailure("203.0.113.5"));
    }

    @Test
    void anExhaustedBucketTurnsTheFailureIntoA429WithItsRetryAfter() throws Exception {
        exhausted = true;

        var limited = throttle.onFailure(request(), new AuthenticationFailedException(Reason.INVALID_TOKEN));

        assertThat(limited).isPresent();
        assertThat(limited.get().retryAfterSeconds()).isEqualTo(9);
    }

    @Test
    void anExpiredTokenOrAnInactiveUserIsNeverTurnedInto429EvenIfTheBucketIsExhausted() throws Exception {
        exhausted = true;

        assertThat(throttle.onFailure(request(), new AuthenticationFailedException(Reason.TOKEN_EXPIRED))).isEmpty();
        assertThat(throttle.onFailure(request(), new AuthenticationFailedException(Reason.USER_INACTIVE))).isEmpty();
        assertThat(checked).isEmpty();
    }
}
