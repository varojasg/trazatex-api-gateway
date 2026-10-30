package com.nodotextil.trazatex.apigateway.infrastructure.ratelimit;

import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import com.nodotextil.trazatex.apigateway.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 03c con el Gateway completo: login por IP (5/min en la prueba), invitaciones por IP (4), y en las rutas autenticadas un cubo
 * por usuario (6) y otro por empresa (10) que deben cumplirse a la vez. Cada prueba arranca un contexto nuevo: los cubos viven en
 * memoria y todas las peticiones salen de la misma IP.
 */
@TestPropertySource(properties = {"trazatex.rate-limit.login.permits=5", "trazatex.rate-limit.invitation.permits=4",
        "trazatex.rate-limit.user.permits=6", "trazatex.rate-limit.company.permits=10"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class RateLimitHttpTest extends GatewayTestSupport {

    @BeforeEach
    void stubService() {
        ORG_ACCESS.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
    }

    private int login(String... extraHeaders) {
        var request = anonymous.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i + 1 < extraHeaders.length; i += 2) {
            request = request.header(extraHeaders[i], extraHeaders[i + 1]);
        }
        return request.bodyValue("{}").exchange().returnResult(String.class).getStatus().value();
    }

    private int authed(String token) {
        return anonymous.get().uri("/api/companies").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange()
                .returnResult(String.class).getStatus().value();
    }

    private static String token(UUID user, UUID company) {
        return TestTokens.forUser(user, "OPERATOR", company);
    }

    private int reachedService(String path) {
        return ORG_ACCESS.findAll(anyRequestedFor(urlPathEqualTo(path))).size();
    }

    @Test
    void loginIsLimitedPerIpAndTheRejectionIsAnApiErrorWithRetryAfter() {
        for (int i = 0; i < 5; i++) {
            assertThat(login()).as("petición %d".formatted(i + 1)).isEqualTo(200);
        }

        EntityExchangeResult<byte[]> rejected = anonymous.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange().expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.code").isEqualTo("TOO_MANY_REQUESTS")
                .jsonPath("$.message").value(m -> assertThat(m.toString()).contains("intenta de nuevo en"))
                .jsonPath("$.details").isArray()
                .jsonPath("$.timestamp").isNotEmpty()
                .returnResult();

        long retryAfter = Long.parseLong(rejected.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).as("segundos enteros, hasta un minuto (60 permisos... aquí 5 por minuto = 12 s por permiso)").isBetween(1L, 60L);
        assertThat(reachedService("/api/auth/login")).as("lo rechazado no llega al servicio").isEqualTo(5);
    }

    @Test
    void aForgedForwardedForDoesNotChangeBucketNorEvadeTheLimit() {
        // cada petición dice venir de una IP distinta: no sirve, la IP es la de la conexión
        for (int i = 0; i < 5; i++) {
            assertThat(login("X-Forwarded-For", "10.9.8." + i, "X-Real-IP", "10.7.7." + i, "Forwarded", "for=10.6.6." + i)).isEqualTo(200);
        }

        assertThat(login("X-Forwarded-For", "10.9.8.250")).isEqualTo(429);
        assertThat(login("X-Forwarded-For", "203.0.113.77, 198.51.100.1")).isEqualTo(429);
        assertThat(login("X-Forwarded-For", "::1")).isEqualTo(429);
        assertThat(reachedService("/api/auth/login")).isEqualTo(5);
    }

    @Test
    void exhaustingLoginDoesNotAffectTheInvitationsNorTheAuthenticatedRoutes() {
        for (int i = 0; i < 7; i++) {
            login();
        }

        anonymous.get().uri("/api/invitations/abc123").exchange().expectStatus().isOk();
        assertThat(authed(token(UUID.randomUUID(), UUID.randomUUID()))).isEqualTo(200);
    }

    @Test
    void thePreflightRequestsOfTheBrowserDoNotSpendPermits() {
        for (int i = 0; i < 30; i++) {
            anonymous.options().uri("/api/auth/login").header(HttpHeaders.ORIGIN, "http://localhost:5173")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST").exchange().expectStatus().isOk();
        }

        for (int i = 0; i < 5; i++) {
            assertThat(login()).isEqualTo(200);
        }
        assertThat(login()).isEqualTo(429);
    }

    @Test
    void theTwoPublicInvitationRoutesShareOneBucketPerIp() {
        anonymous.get().uri("/api/invitations/abc123").exchange().expectStatus().isOk();
        anonymous.get().uri("/api/invitations/abc123").exchange().expectStatus().isOk();
        anonymous.post().uri("/api/invitations/abc123/accept").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();
        anonymous.post().uri("/api/invitations/abc123/accept").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();

        anonymous.get().uri("/api/invitations/abc123").exchange().expectStatus().isEqualTo(429).expectHeader().exists(HttpHeaders.RETRY_AFTER);
        anonymous.post().uri("/api/invitations/otro/accept").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isEqualTo(429);
        assertThat(login()).as("el login tiene su propio cubo").isEqualTo(200);
    }

    @Test
    void theUserBucketLimitsOneUserWithoutAffectingOthersOfTheSameCompany() {
        UUID company = UUID.randomUUID();
        String ana = token(UUID.randomUUID(), company);
        String luis = token(UUID.randomUUID(), company);
        for (int i = 0; i < 6; i++) {
            assertThat(authed(ana)).isEqualTo(200);
        }

        anonymous.get().uri("/api/companies").header(HttpHeaders.AUTHORIZATION, "Bearer " + ana).exchange()
                .expectStatus().isEqualTo(429).expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody().jsonPath("$.code").isEqualTo("TOO_MANY_REQUESTS");
        assertThat(authed(luis)).isEqualTo(200);
        assertThat(reachedService("/api/companies")).isEqualTo(7);
    }

    @Test
    void theCompanyBucketLimitsEveryoneOfTheCompanyEvenIfTheirOwnBucketIsFull() {
        UUID company = UUID.randomUUID();
        String ana = token(UUID.randomUUID(), company);
        String luis = token(UUID.randomUUID(), company);
        String rosa = token(UUID.randomUUID(), company);
        for (int i = 0; i < 6; i++) {
            assertThat(authed(ana)).isEqualTo(200);   // empresa 6/10
        }
        for (int i = 0; i < 4; i++) {
            assertThat(authed(luis)).isEqualTo(200);  // empresa 10/10
        }

        assertThat(authed(luis)).as("a Luis le quedaban 2 de los suyos, pero la empresa se agotó").isEqualTo(429);
        assertThat(authed(rosa)).as("Rosa no ha hecho nada, pero es de la misma empresa").isEqualTo(429);
        assertThat(authed(token(UUID.randomUUID(), UUID.randomUUID()))).as("otra empresa no se ve afectada").isEqualTo(200);
        assertThat(reachedService("/api/companies")).isEqualTo(11);
    }

    @Test
    void theOwnerHasNoCompanyAndOnlyTheUserBucketApplies() {
        String owner = TestTokens.forUser(UUID.randomUUID(), "LICENSE_OWNER", null);
        for (int i = 0; i < 6; i++) {
            assertThat(authed(owner)).isEqualTo(200);
        }

        assertThat(authed(owner)).isEqualTo(429);
        assertThat(authed(token(UUID.randomUUID(), UUID.randomUUID()))).isEqualTo(200);
    }

    @Test
    void requestsThatFailAuthenticationHaveNoIdentityAndDoNotSpendPermits() {
        UUID user = UUID.randomUUID();
        UUID company = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            assertThat(authed("token.invalido.%d".formatted(i))).isEqualTo(401);
            anonymous.get().uri("/api/companies").exchange().expectStatus().isUnauthorized();
        }

        for (int i = 0; i < 6; i++) {
            assertThat(authed(token(user, company))).isEqualTo(200);
        }
        assertThat(authed(token(user, company))).isEqualTo(429);
    }

    @Test
    void aRequestRejectedByThePathGuardDoesNotSpendPermits() {
        UUID user = UUID.randomUUID();
        UUID company = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            anonymous.get().uri("/internal/batches/x").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(user, company)).exchange().expectStatus().isNotFound();
        }

        for (int i = 0; i < 6; i++) {
            assertThat(authed(token(user, company))).isEqualTo(200);
        }
    }

    @Test
    void theRejectionCarriesTheCorsHeadersSoTheBrowserCanReadRetryAfter() {
        UUID company = UUID.randomUUID();
        String token = token(UUID.randomUUID(), company);
        for (int i = 0; i < 6; i++) {
            authed(token);
        }

        anonymous.get().uri("/api/companies").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .exchange().expectStatus().isEqualTo(429)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
                .expectHeader().value(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, v -> assertThat(v).contains("Retry-After"))
                .expectHeader().exists(HttpHeaders.RETRY_AFTER);
    }

    @Test
    void manySimultaneousRequestsOfOneUserNeverGoAboveTheLimit() throws Exception {
        String token = token(UUID.randomUUID(), UUID.randomUUID());
        int requests = 60;
        ExecutorService pool = Executors.newFixedThreadPool(30);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            Callable<Integer> call = () -> {
                go.await();
                return authed(token);
            };
            results.add(pool.submit(call));
        }
        go.countDown();
        int ok = 0;
        int limited = 0;
        for (Future<Integer> result : results) {
            int status = result.get();
            if (status == 200) {
                ok++;
            } else if (status == 429) {
                limited++;
            }
        }
        pool.shutdown();

        assertThat(ok).isEqualTo(6);
        assertThat(limited).isEqualTo(requests - 6);
        assertThat(reachedService("/api/companies")).isEqualTo(6);
    }
}
