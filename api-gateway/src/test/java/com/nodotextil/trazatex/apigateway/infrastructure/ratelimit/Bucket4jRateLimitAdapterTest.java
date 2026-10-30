package com.nodotextil.trazatex.apigateway.infrastructure.ratelimit;

import com.nodotextil.trazatex.apigateway.domain.model.RateLimit;
import com.nodotextil.trazatex.apigateway.domain.model.RateLimitDecision;
import com.nodotextil.trazatex.apigateway.domain.model.RateLimitScope;
import io.github.bucket4j.TimeMeter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Bucket4j y Caffeine de verdad, con un reloj de mentira compartido: el paso del tiempo se prueba sin esperar. */
class Bucket4jRateLimitAdapterTest {

    private static final RateLimit TEN_PER_MINUTE = new RateLimit(10, Duration.ofMinutes(1));
    private static final Duration IDLE = Duration.ofMinutes(10);

    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final TimeMeter meter = new TimeMeter() {
        @Override
        public long currentTimeNanos() {
            return nanos.get();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
    };
    private final Bucket4jRateLimitAdapter adapter = new Bucket4jRateLimitAdapter(IDLE, 1_000, nanos::get, meter);

    private void advance(Duration duration) {
        nanos.addAndGet(duration.toNanos());
    }

    private int consumeUntilRejected(RateLimitScope scope, String key, RateLimit limit, int attempts) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (adapter.tryConsume(scope, key, limit).allowed()) {
                allowed++;
            }
        }
        return allowed;
    }

    @Test
    void allowsExactlyTheConfiguredPermitsAndThenRejectsWithARetryAfter() {
        assertThat(consumeUntilRejected(RateLimitScope.USER, "u1", TEN_PER_MINUTE, 25)).isEqualTo(10);

        RateLimitDecision rejected = adapter.tryConsume(RateLimitScope.USER, "u1", TEN_PER_MINUTE);
        assertThat(rejected.allowed()).isFalse();
        // reposición continua: 10 permisos por minuto = uno cada 6 s
        assertThat(rejected.retryAfter()).isBetween(Duration.ofMillis(5_900), Duration.ofSeconds(6));
    }

    @Test
    void permitsComeBackGraduallyNotAllAtOnceAtTheEndOfTheWindow() {
        consumeUntilRejected(RateLimitScope.USER, "u1", TEN_PER_MINUTE, 10);

        advance(Duration.ofSeconds(6));
        assertThat(adapter.tryConsume(RateLimitScope.USER, "u1", TEN_PER_MINUTE).allowed()).isTrue();
        assertThat(adapter.tryConsume(RateLimitScope.USER, "u1", TEN_PER_MINUTE).allowed()).isFalse();

        advance(Duration.ofSeconds(30));   // 5 permisos más
        assertThat(consumeUntilRejected(RateLimitScope.USER, "u1", TEN_PER_MINUTE, 20)).isEqualTo(5);

        advance(Duration.ofMinutes(5));   // pasada mucha ventana: lleno de nuevo, nunca por encima de la capacidad
        assertThat(consumeUntilRejected(RateLimitScope.USER, "u1", TEN_PER_MINUTE, 50)).isEqualTo(10);
    }

    @Test
    void eachKeyAndEachScopeHasItsOwnBucket() {
        consumeUntilRejected(RateLimitScope.USER, "u1", TEN_PER_MINUTE, 10);

        assertThat(adapter.tryConsume(RateLimitScope.USER, "u1", TEN_PER_MINUTE).allowed()).isFalse();
        assertThat(adapter.tryConsume(RateLimitScope.USER, "u2", TEN_PER_MINUTE).allowed()).as("otra clave").isTrue();
        assertThat(adapter.tryConsume(RateLimitScope.COMPANY, "u1", TEN_PER_MINUTE).allowed()).as("otro ámbito, misma clave").isTrue();
        assertThat(adapter.tryConsume(RateLimitScope.LOGIN_IP, "u1", TEN_PER_MINUTE).allowed()).isTrue();
    }

    @Test
    void aRefundGivesBackOnePermitButNeverGoesAboveTheCapacity() {
        consumeUntilRejected(RateLimitScope.USER, "u1", TEN_PER_MINUTE, 10);
        assertThat(adapter.availableTokens(RateLimitScope.USER, "u1")).isZero();

        adapter.refund(RateLimitScope.USER, "u1", TEN_PER_MINUTE);
        assertThat(adapter.availableTokens(RateLimitScope.USER, "u1")).isEqualTo(1);
        assertThat(adapter.tryConsume(RateLimitScope.USER, "u1", TEN_PER_MINUTE).allowed()).isTrue();

        for (int i = 0; i < 50; i++) {
            adapter.refund(RateLimitScope.USER, "u1", TEN_PER_MINUTE);
        }
        assertThat(adapter.availableTokens(RateLimitScope.USER, "u1")).isEqualTo(10);
    }

    @Test
    void aRefundForABucketThatDoesNotExistDoesNothing() {
        adapter.refund(RateLimitScope.USER, "fantasma", TEN_PER_MINUTE);

        assertThat(adapter.size(RateLimitScope.USER)).isZero();
    }

    @Test
    void anIdleBucketExpiresAfterTheInactivityTimeAndTheNextOneStartsFull() {
        adapter.tryConsume(RateLimitScope.USER, "u1", TEN_PER_MINUTE);
        assertThat(adapter.size(RateLimitScope.USER)).isEqualTo(1);

        advance(IDLE.plusSeconds(1));

        assertThat(adapter.size(RateLimitScope.USER)).as("el cubo inactivo se descartó").isZero();
        assertThat(adapter.availableTokens(RateLimitScope.USER, "u1")).isEqualTo(-1);
    }

    @Test
    void aBucketUsedWithinTheInactivityTimeIsNotExpired() {
        adapter.tryConsume(RateLimitScope.USER, "u1", TEN_PER_MINUTE);

        for (int i = 0; i < 5; i++) {
            advance(Duration.ofMinutes(9));   // cada vez menos de 10 min desde el último uso
            adapter.tryConsume(RateLimitScope.USER, "u1", TEN_PER_MINUTE);
        }

        assertThat(adapter.size(RateLimitScope.USER)).isEqualTo(1);
    }

    @Test
    void expiringAnIdleBucketNeverGrantsMorePermitsThanTheLimitAllows() {
        // El cubo se agota; el límite (10/min) es menor que la inactividad (10 min): cuando expira ya estaría lleno de todos modos
        consumeUntilRejected(RateLimitScope.USER, "u1", TEN_PER_MINUTE, 10);
        advance(Duration.ofSeconds(30));
        assertThat(adapter.tryConsume(RateLimitScope.USER, "u1", TEN_PER_MINUTE).allowed()).isTrue();   // sigue siendo el mismo cubo (vacío casi)
        assertThat(adapter.size(RateLimitScope.USER)).isEqualTo(1);

        advance(IDLE.plusSeconds(1));
        assertThat(consumeUntilRejected(RateLimitScope.USER, "u1", TEN_PER_MINUTE, 50)).isEqualTo(10);
    }

    @Test
    void theNumberOfBucketsIsCappedEvenWithManyDistinctKeys() {
        Bucket4jRateLimitAdapter small = new Bucket4jRateLimitAdapter(IDLE, 100, nanos::get, meter);

        for (int i = 0; i < 20_000; i++) {
            small.tryConsume(RateLimitScope.LOGIN_IP, "ip-" + i, TEN_PER_MINUTE);
        }

        assertThat(small.size(RateLimitScope.LOGIN_IP)).isLessThanOrEqualTo(100);
        assertThat(small.size(RateLimitScope.USER)).as("los otros ámbitos no se tocan").isZero();
    }

    @Test
    void concurrentRequestsNeverGoAboveTheLimit() throws Exception {
        RateLimit limit = new RateLimit(200, Duration.ofMinutes(1));
        int threads = 16;
        int attemptsPerThread = 1_000;
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                go.await();
                for (int i = 0; i < attemptsPerThread; i++) {
                    if (adapter.tryConsume(RateLimitScope.USER, "compartido", limit).allowed()) {
                        allowed.incrementAndGet();
                    }
                }
                return null;
            });
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(allowed.get()).isEqualTo(200);
    }
}
