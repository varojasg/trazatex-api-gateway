package com.nodotextil.trazatex.apigateway.application.usecase;

import com.nodotextil.trazatex.apigateway.application.port.out.RevokedUsersPort;
import com.nodotextil.trazatex.apigateway.application.port.out.TokenVerifierPort;
import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationFailedException;
import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationFailedException.Reason;
import com.nodotextil.trazatex.apigateway.domain.exception.AuthenticationUnavailableException;
import com.nodotextil.trazatex.apigateway.domain.model.AuthenticatedUser;
import com.nodotextil.trazatex.apigateway.domain.model.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticateServiceTest {

    private final List<String> verified = new ArrayList<>();
    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), Role.OPERATOR, UUID.randomUUID());
    private final TokenVerifierPort verifier = token -> {
        if (token.equals("malo")) {
            return Mono.error(new AuthenticationFailedException(Reason.INVALID_TOKEN));
        }
        verified.add(token);
        return Mono.just(user);
    };
    private final Set<UUID> revoked = new HashSet<>();
    private boolean loaded = true;
    private final RevokedUsersPort revokedUsers = new RevokedUsersPort() {
        @Override
        public boolean isLoaded() {
            return loaded;
        }

        @Override
        public boolean isRevoked(UUID userId) {
            return revoked.contains(userId);
        }
    };
    private final AuthenticateService service = new AuthenticateService(verifier, revokedUsers);

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void sinCabeceraEsMissingToken(String header) {
        StepVerifier.create(service.authenticate(header)).expectErrorSatisfies(e -> assertThat(reason(e)).isEqualTo(Reason.MISSING_TOKEN)).verify();
        assertThat(verified).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic abc", "Bearer", "Bearer ", "Bearer     ", "Bearerabc", "Token abc"})
    void unaCabeceraQueNoEsBearerConTokenEsInvalida(String header) {
        StepVerifier.create(service.authenticate(header)).expectErrorSatisfies(e -> assertThat(reason(e)).isEqualTo(Reason.INVALID_TOKEN)).verify();
        assertThat(verified).isEmpty();
    }

    @Test
    void unTokenDeMasDeOchoMilCaracteresNiSeIntentaVerificar() {
        StepVerifier.create(service.authenticate("Bearer " + "a".repeat(AuthenticateService.MAX_TOKEN_LENGTH + 1)))
                .expectErrorSatisfies(e -> assertThat(reason(e)).isEqualTo(Reason.INVALID_TOKEN)).verify();
        assertThat(verified).isEmpty();
    }

    @Test
    void conBearerSeVerificaElTokenSinElPrefijoNiEspacios() {
        StepVerifier.create(service.authenticate("bEaReR  abc.def.ghi  ")).expectNext(user).verifyComplete();
        assertThat(verified).containsExactly("abc.def.ghi");
    }

    @Test
    void aValidTokenOfAnInactiveUserIsRejected() {
        revoked.add(user.userId());

        StepVerifier.create(service.authenticate("Bearer abc")).expectErrorSatisfies(e -> assertThat(reason(e)).isEqualTo(Reason.USER_INACTIVE)).verify();
    }

    @Test
    void theSameTokenWorksAgainOnceTheUserIsActiveAgain() {
        revoked.add(user.userId());
        StepVerifier.create(service.authenticate("Bearer abc")).expectError(AuthenticationFailedException.class).verify();

        revoked.remove(user.userId());

        StepVerifier.create(service.authenticate("Bearer abc")).expectNext(user).verifyComplete();
    }

    @Test
    void untilTheInactiveListIsLoadedNobodyGetsInButTheCauseIsUnavailableNotAnInvalidToken() {
        loaded = false;

        StepVerifier.create(service.authenticate("Bearer abc")).expectError(AuthenticationUnavailableException.class).verify();
    }

    @Test
    void anInvalidTokenIsAlways401EvenWhileTheInactiveListIsStillLoading() {
        loaded = false;

        StepVerifier.create(service.authenticate("Bearer malo")).expectErrorSatisfies(e -> assertThat(reason(e)).isEqualTo(Reason.INVALID_TOKEN)).verify();
        StepVerifier.create(service.authenticate(null)).expectErrorSatisfies(e -> assertThat(reason(e)).isEqualTo(Reason.MISSING_TOKEN)).verify();
    }

    private static Reason reason(Throwable e) {
        return ((AuthenticationFailedException) e).reason();
    }
}
