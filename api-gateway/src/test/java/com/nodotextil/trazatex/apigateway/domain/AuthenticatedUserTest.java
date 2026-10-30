package com.nodotextil.trazatex.apigateway.domain;

import com.nodotextil.trazatex.apigateway.domain.model.AuthenticatedUser;
import com.nodotextil.trazatex.apigateway.domain.model.Role;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedUserTest {

    @Test
    void soloElDuenoDeLicenciaVaSinEmpresa() {
        assertThat(new AuthenticatedUser(UUID.randomUUID(), Role.LICENSE_OWNER, null).companyId()).isNull();
        assertThatThrownBy(() -> new AuthenticatedUser(UUID.randomUUID(), Role.LICENSE_OWNER, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticatedUser(UUID.randomUUID(), Role.OPERATOR, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthenticatedUser(UUID.randomUUID(), Role.COMPANY_ADMIN, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
