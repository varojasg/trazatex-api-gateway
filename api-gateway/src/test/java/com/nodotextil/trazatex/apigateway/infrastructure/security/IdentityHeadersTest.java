package com.nodotextil.trazatex.apigateway.infrastructure.security;

import com.nodotextil.trazatex.apigateway.domain.model.AuthenticatedUser;
import com.nodotextil.trazatex.apigateway.domain.model.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityHeadersTest {

    @Test
    void stripBorraSoloLosPrefijosReservadosSinImportarMayusculasNiGuionBajo() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "a");
        headers.add("x-user-role", "b");
        headers.add("X_User_Foo", "c");
        headers.add("X-COMPANY-ID", "d");
        headers.add("X_Company_Name", "e");
        headers.add("X-Forwarded-For", "1.2.3.4");
        headers.add("X-Users", "no se toca");
        headers.add("X-Company", "no se toca");
        headers.add("Authorization", "Bearer x");

        IdentityHeaders.strip(headers);

        assertThat(headers.headerNames()).containsExactlyInAnyOrder("X-Forwarded-For", "X-Users", "X-Company", "Authorization");
    }

    @Test
    void injectReemplazaLoQueHubiera() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-User-Id", "falso");
        UUID user = UUID.randomUUID();

        IdentityHeaders.inject(headers, new AuthenticatedUser(user, Role.LICENSE_OWNER, null));

        assertThat(headers.get("X-User-Id")).containsExactly(user.toString());
        assertThat(headers.get("X-User-Role")).containsExactly("LICENSE_OWNER");
        assertThat(headers.containsHeader("X-Company-Id")).isFalse();
    }
}
