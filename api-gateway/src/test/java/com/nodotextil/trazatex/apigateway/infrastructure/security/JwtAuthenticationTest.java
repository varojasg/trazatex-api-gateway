package com.nodotextil.trazatex.apigateway.infrastructure.security;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nodotextil.trazatex.apigateway.GatewayTestSupport;
import com.nodotextil.trazatex.apigateway.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

/** 03b: rutas protegidas exigen un JWT RS256 válido (clave del JWKS por kid); las tres públicas no. */
class JwtAuthenticationTest extends GatewayTestSupport {

    private static final String PROTECTED = "/api/companies";

    @BeforeEach
    void stubService() {
        ORG_ACCESS.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
    }

    private WebTestClient.ResponseSpec get(String authorization) {
        WebTestClient.RequestHeadersSpec<?> request = anonymous.get().uri(PROTECTED);
        return (authorization == null ? request : request.header(HttpHeaders.AUTHORIZATION, authorization)).exchange();
    }

    private void expectRejected(String authorization, String code) {
        get(authorization).expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .expectBody().jsonPath("$.code").isEqualTo(code).jsonPath("$.message").isNotEmpty().jsonPath("$.timestamp").isNotEmpty();
        assertThat(ORG_ACCESS.findAll(anyRequestedFor(anyUrl()))).as("la petición no debe llegar al servicio").isEmpty();
    }

    @Test
    void conUnTokenValidoLaPeticionLlegaAlServicio() {
        get("Bearer " + TestTokens.operator()).expectStatus().isOk();
        assertThat(ORG_ACCESS.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
    }

    @Test
    void elEsquemaBearerNoDistingueMayusculas() {
        get("bearer " + TestTokens.operator()).expectStatus().isOk();
    }

    @Test
    void elDuenoDeLicenciaSinEmpresaTambienEntra() {
        get("Bearer " + TestTokens.forUser(UUID.randomUUID(), "LICENSE_OWNER", null)).expectStatus().isOk();
    }

    @Test
    void sinCabeceraAuthorizationDa401() {
        expectRejected(null, "MISSING_TOKEN");
    }

    @ParameterizedTest
    @MethodSource("malformedHeaders")
    void unaCabeceraQueNoEsBearerConTokenDa401(String header) {
        expectRejected(header, "INVALID_TOKEN");
    }

    static Stream<String> malformedHeaders() {
        return Stream.of("Basic dXNlcjpwYXNz", "Bearer", "Bearer ", "Bearer    ", "Token abc", "abc.def.ghi",
                "Bearer abc", "Bearer a.b.c", "Bearer " + "x".repeat(7000));
    }

    @Test
    void unTokenVencidoDa401TokenExpired() {
        JWTClaimsSet expired = TestTokens.operatorClaims()
                .issueTime(Date.from(Instant.now().minusSeconds(7200))).expirationTime(Date.from(Instant.now().minusSeconds(3600))).build();
        expectRejected("Bearer " + TestTokens.sign(TestTokens.KEY, expired), "TOKEN_EXPIRED");
    }

    @Test
    void unTokenFirmadoConOtraClaveConElMismoKidDa401() {
        RSAKey attacker = TestTokens.generate(TestTokens.KID);
        expectRejected("Bearer " + TestTokens.sign(attacker, TestTokens.operatorClaims().build()), "INVALID_TOKEN");
    }

    @Test
    void unTokenConClaimsAlteradosDa401() {
        String[] parts = TestTokens.operator().split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"11111111-1111-1111-1111-111111111111\",\"role\":\"LICENSE_OWNER\",\"iat\":1,\"exp\":99999999999}".getBytes());
        expectRejected("Bearer " + parts[0] + "." + forgedPayload + "." + parts[2], "INVALID_TOKEN");
    }

    @Test
    void laConfusionDeAlgoritmoHs256ConLaClavePublicaComoSecretoSeRechaza() {
        expectRejected("Bearer " + TestTokens.hs256WithPublicKeyAsSecret(TestTokens.operatorClaims().build()), "INVALID_TOKEN");
    }

    @Test
    void unTokenSinFirmaAlgNoneSeRechaza() {
        expectRejected("Bearer " + TestTokens.unsigned(TestTokens.operatorClaims().build()), "INVALID_TOKEN");
    }

    @Test
    void unKidQueElJwksNoTieneSeRechaza() {
        RSAKey other = TestTokens.generate("otro-kid");
        expectRejected("Bearer " + TestTokens.sign(other, TestTokens.operatorClaims().build()), "INVALID_TOKEN");
    }

    @ParameterizedTest
    @MethodSource("badClaims")
    void unTokenFirmadoPeroConClaimsInvalidosDa401(String description, JWTClaimsSet claims) {
        expectRejected("Bearer " + TestTokens.sign(TestTokens.KEY, claims), "INVALID_TOKEN");
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> badClaims() {
        UUID user = TestTokens.USER_ID;
        UUID company = TestTokens.COMPANY_ID;
        Date iat = new Date();
        Date exp = Date.from(Instant.now().plusSeconds(3600));
        return Stream.of(
                args("sin sub", new JWTClaimsSet.Builder().claim("role", "OPERATOR").claim("companyId", company.toString()).issueTime(iat).expirationTime(exp).build()),
                args("sin role", new JWTClaimsSet.Builder().subject(user.toString()).claim("companyId", company.toString()).issueTime(iat).expirationTime(exp).build()),
                args("sin exp", new JWTClaimsSet.Builder().subject(user.toString()).claim("role", "OPERATOR").claim("companyId", company.toString()).issueTime(iat).build()),
                args("sin iat", new JWTClaimsSet.Builder().subject(user.toString()).claim("role", "OPERATOR").claim("companyId", company.toString()).expirationTime(exp).build()),
                args("sub no es UUID", TestTokens.claims(user, "OPERATOR", company).subject("admin").build()),
                args("rol desconocido", TestTokens.claims(user, "ROOT", company).build()),
                args("rol en minúsculas", TestTokens.claims(user, "operator", company).build()),
                args("operario sin empresa", TestTokens.claims(user, "OPERATOR", null).build()),
                args("administrador sin empresa", TestTokens.claims(user, "COMPANY_ADMIN", null).build()),
                args("dueño con empresa", TestTokens.claims(user, "LICENSE_OWNER", company).build()),
                args("companyId no es UUID", TestTokens.claims(user, "OPERATOR", null).claim("companyId", "42").build()),
                args("role no es texto", TestTokens.claims(user, "OPERATOR", company).claim("role", 7).build()));
    }

    private static org.junit.jupiter.params.provider.Arguments args(String description, JWTClaimsSet claims) {
        return org.junit.jupiter.params.provider.Arguments.of(description, claims);
    }

    @Test
    void lasTresRutasPublicasPasanSinToken() {
        anonymous.post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();
        anonymous.get().uri("/api/invitations/abc123").exchange().expectStatus().isOk();
        anonymous.post().uri("/api/invitations/abc123/accept").contentType(MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isOk();

        assertThat(ORG_ACCESS.findAll(anyRequestedFor(anyUrl()))).hasSize(3);
    }

    @Test
    void lasRutasPublicasPasanTambienConUnTokenInvalido() {
        // El token de una sesión vencida no debe impedir iniciar sesión de nuevo ni aceptar una invitación
        anonymous.post().uri("/api/auth/login").header(HttpHeaders.AUTHORIZATION, "Bearer basura").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}").exchange().expectStatus().isOk();
    }

    @ParameterizedTest
    @MethodSource("notPublic")
    void rutasParecidasPeroNoPublicasExigenToken(HttpMethod method, String path) {
        anonymous.method(method).uri(path).exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("MISSING_TOKEN");
        assertThat(ORG_ACCESS.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> notPublic() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.GET, "/api/auth/login"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.PUT, "/api/auth/login"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.POST, "/api/auth/login/"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.POST, "/api/auth/login/x"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.POST, "/api/auth/anything"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.POST, "/api/invitations/abc123"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.POST, "/api/invitations/operators"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.POST, "/api/invitations/abc123/cancel"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.GET, "/api/invitations/abc123/accept"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.GET, "/api/invitations/abc123/"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.GET, "/api/invitations/a/b"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.POST, "/api/invitations/abc123/accept/"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.POST, "/api/invitations/abc/accept/x"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.DELETE, "/api/invitations/abc123"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.GET, "/api/companies"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.GET, "/api/license"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.GET, "/api/users"),
                org.junit.jupiter.params.provider.Arguments.of(HttpMethod.GET, "/api/invitations/x/y/z"));
    }

    @Test
    void elPreflightCorsNoNecesitaToken() {
        anonymous.options().uri(PROTECTED).header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET").header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization")
                .exchange().expectStatus().isOk();
    }

    @Test
    void ElRechazo401LlevaLasCabecerasCorsParaQueElNavegadorPuedaLeerlo() {
        anonymous.get().uri(PROTECTED).header(HttpHeaders.ORIGIN, "http://localhost:5173").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173");
    }

    @Test
    void rutasSinServicioNoSeDelatanComo401() {
        // Sin ruta no hay filtros: 404 aunque no haya token (no se revela nada y no hay nada que proteger)
        anonymous.get().uri("/api/no-registrado/resource").exchange().expectStatus().isNotFound();
        anonymous.get().uri("/internal/batches/11111111-1111-1111-1111-111111111111/phase-transitions")
                .exchange().expectStatus().isNotFound();
        anonymous.get().uri("/api/quality/batches/1/controls").exchange().expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("MISSING_TOKEN");
    }
}
