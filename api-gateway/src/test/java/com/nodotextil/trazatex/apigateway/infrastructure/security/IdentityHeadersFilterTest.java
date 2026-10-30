package com.nodotextil.trazatex.apigateway.infrastructure.security;

import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import com.nodotextil.trazatex.apigateway.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 03b-2: el servicio solo ve la identidad que puso el Gateway (la del token). Todo {@code X-User-*} y {@code X-Company-*} que
 * mande el cliente se borra, con token o sin él, y lo demás pasa intacto.
 */
class IdentityHeadersFilterTest extends GatewayTestSupport {

    private static final UUID EVIL = UUID.fromString("66666666-6666-6666-6666-666666666666");

    @BeforeEach
    void stubService() {
        ORG_ACCESS.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{}")));
    }

    private LoggedRequest received() {
        List<LoggedRequest> all = ORG_ACCESS.findAll(anyRequestedFor(anyUrl()));
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    private static List<String> identityHeaderNames(LoggedRequest request) {
        return request.getHeaders().all().stream().map(HttpHeader::key)
                .filter(k -> k.toLowerCase(Locale.ROOT).replace('_', '-').startsWith("x-user-")
                        || k.toLowerCase(Locale.ROOT).replace('_', '-').startsWith("x-company-"))
                .toList();
    }

    @Test
    void elServicioRecibeLaIdentidadDelTokenAunqueElClienteMandeOtra() {
        UUID userId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        String token = TestTokens.forUser(userId, "COMPANY_ADMIN", companyId);

        anonymous.get().uri("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-User-Id", EVIL.toString())
                .header("X-User-Role", "LICENSE_OWNER")
                .header("X-Company-Id", EVIL.toString())
                .exchange().expectStatus().isOk();

        LoggedRequest request = received();
        assertThat(request.header("X-User-Id").values()).containsExactly(userId.toString());
        assertThat(request.header("X-User-Role").values()).containsExactly("COMPANY_ADMIN");
        assertThat(request.header("X-Company-Id").values()).containsExactly(companyId.toString());
        assertThat(identityHeaderNames(request)).containsExactlyInAnyOrder("X-User-Id", "X-User-Role", "X-Company-Id");
    }

    @Test
    void elDuenoNoTieneEmpresaYUnaEmpresaFalsificadaNoLlega() {
        UUID owner = UUID.randomUUID();
        anonymous.get().uri("/api/license").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.forUser(owner, "LICENSE_OWNER", null))
                .header("X-Company-Id", EVIL.toString()).exchange().expectStatus().isOk();

        LoggedRequest request = received();
        assertThat(request.header("X-User-Id").values()).containsExactly(owner.toString());
        assertThat(request.header("X-User-Role").values()).containsExactly("LICENSE_OWNER");
        assertThat(request.containsHeader("X-Company-Id")).as("el Dueño no pertenece a una empresa").isFalse();
    }

    @Test
    void sePuedenFalsificarConOtraCapitalizacionConVariasVecesYConGuionBajo() {
        anonymous.get().uri("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.operator())
                .header("x-user-id", EVIL.toString())
                .header("X-USER-ROLE", "LICENSE_OWNER")
                .header("X-User-Id", EVIL.toString(), "otro")
                .header("X_User_Id", EVIL.toString())
                .header("X_COMPANY_ID", EVIL.toString())
                .header("x-company-id", EVIL.toString())
                .exchange().expectStatus().isOk();

        LoggedRequest request = received();
        assertThat(request.header("X-User-Id").values()).containsExactly(TestTokens.USER_ID.toString());
        assertThat(request.header("X-User-Role").values()).containsExactly("OPERATOR");
        assertThat(request.header("X-Company-Id").values()).containsExactly(TestTokens.COMPANY_ID.toString());
        assertThat(identityHeaderNames(request)).hasSize(3);
    }

    @Test
    void cualquierX_UserOX_CompanyAjenoSeBorra() {
        anonymous.get().uri("/api/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.operator())
                .header("X-User-Permissions", "ALL").header("X-User-Email", "x@y.z").header("X-Company-Name", "Evil SAC")
                .header("X-Company-Admin", "true").exchange().expectStatus().isOk();

        assertThat(identityHeaderNames(received())).containsExactlyInAnyOrder("X-User-Id", "X-User-Role", "X-Company-Id");
    }

    @Test
    void enLasRutasPublicasSeBorranYNoSeInyectaNada() {
        anonymous.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .header("X-User-Id", EVIL.toString()).header("X-User-Role", "LICENSE_OWNER").header("X-Company-Id", EVIL.toString())
                .exchange().expectStatus().isOk();

        assertThat(identityHeaderNames(received())).isEmpty();
    }

    @Test
    void enUnaRutaPublicaConTokenValidoTampocoSeInyecta() {
        anonymous.get().uri("/api/invitations/abc123").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.operator())
                .header("X-User-Id", EVIL.toString()).exchange().expectStatus().isOk();

        assertThat(identityHeaderNames(received())).isEmpty();
    }

    @Test
    void siElTokenFallaNoLlegaNadaAlServicio() {
        anonymous.get().uri("/api/users").header("X-User-Id", EVIL.toString()).header("X-User-Role", "LICENSE_OWNER")
                .exchange().expectStatus().isUnauthorized();

        assertThat(ORG_ACCESS.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void lasDemasCabecerasPasanIntactasIncluidasLasX() {
        anonymous.post().uri("/api/companies").header(HttpHeaders.AUTHORIZATION, "Bearer " + TestTokens.operator())
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .header("Idempotency-Key", "6f0c5b0e-6e89-4b43-9d1c-0f1a2b3c4d5e")
                .header("X-Request-Source", "web").header("X-Usage", "ok").header("X-Companyless", "ok")
                .exchange().expectStatus().isOk();

        LoggedRequest request = received();
        assertThat(request.header("Idempotency-Key").values()).containsExactly("6f0c5b0e-6e89-4b43-9d1c-0f1a2b3c4d5e");
        assertThat(request.header("X-Request-Source").values()).containsExactly("web");
        assertThat(request.header("Content-Type").values()).containsExactly("application/json");
        assertThat(request.header("Authorization").isPresent()).as("el JWT se propaga (ADR-009: auditoría)").isTrue();
        // "X-Usage" y "X-Companyless" no empiezan por "X-User-" / "X-Company-": no se tocan
        assertThat(request.header("X-Usage").values()).containsExactly("ok");
        assertThat(request.header("X-Companyless").values()).containsExactly("ok");
    }
}
