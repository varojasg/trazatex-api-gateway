package com.nodotextil.trazatex.apigateway.infrastructure.revocation;

import com.nodotextil.trazatex.apigateway.RevocationEvents;
import com.nodotextil.trazatex.apigateway.infrastructure.revocation.UserAccountEventParser.Ignored;
import com.nodotextil.trazatex.apigateway.infrastructure.revocation.UserAccountEventParser.Unreadable;
import com.nodotextil.trazatex.apigateway.infrastructure.revocation.UserAccountEventParser.Update;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserAccountEventParserTest {

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final UserAccountEventParser parser = new UserAccountEventParser(JsonMapper.builder().build());

    @Test
    void inactiveRevokesAndActiveDoesNot() {
        assertThat(parser.parse(RevocationEvents.userUpdated(USER, "INACTIVE"))).isEqualTo(new Update(USER, "INACTIVE", true, true));
        assertThat(parser.parse(RevocationEvents.userUpdated(USER, "ACTIVE"))).isEqualTo(new Update(USER, "ACTIVE", false, true));
    }

    @Test
    void unknownFieldsAreIgnoredAtAnyLevel() {
        assertThat(parser.parse(RevocationEvents.userUpdatedWithUnknownFields(USER, "INACTIVE"))).isEqualTo(new Update(USER, "INACTIVE", true, true));
    }

    @Test
    void onlyUserIdAndStatusMatterEvenTheRestOfTheEnvelopeMayBeMissing() {
        assertThat(parser.parse("{\"payload\":{\"userId\":\"" + USER + "\",\"status\":\"INACTIVE\"}}")).isEqualTo(new Update(USER, "INACTIVE", true, true));
    }

    @Test
    void anUnknownStatusRevokesAndIsFlagged() {
        assertThat(parser.parse(RevocationEvents.userUpdated(USER, "SUSPENDED"))).isEqualTo(new Update(USER, "SUSPENDED", true, false));
        assertThat(parser.parse(RevocationEvents.userUpdated(USER, "active"))).as("los nombres son exactos").isEqualTo(new Update(USER, "active", true, false));
    }

    @Test
    void anotherEventTypeIsNotOursAndIsIgnored() {
        assertThat(parser.parse("{\"type\":\"CompanyStatusChanged\",\"payload\":{\"companyId\":\"x\",\"status\":\"INACTIVE\"}}"))
                .isEqualTo(new Ignored("CompanyStatusChanged"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "no es json", "{", "[]", "\"texto\"", "42", "null", "{}", "{\"payload\":null}", "{\"payload\":\"x\"}", "{\"payload\":[]}",
            // falta userId
            "{\"payload\":{\"status\":\"INACTIVE\"}}",
            "{\"payload\":{\"userId\":null,\"status\":\"INACTIVE\"}}",
            "{\"payload\":{\"userId\":42,\"status\":\"INACTIVE\"}}",
            "{\"payload\":{\"userId\":\"no-es-uuid\",\"status\":\"INACTIVE\"}}",
            // falta status
            "{\"payload\":{\"userId\":\"11111111-1111-1111-1111-111111111111\"}}",
            "{\"payload\":{\"userId\":\"11111111-1111-1111-1111-111111111111\",\"status\":null}}",
            "{\"payload\":{\"userId\":\"11111111-1111-1111-1111-111111111111\",\"status\":\"\"}}",
            "{\"payload\":{\"userId\":\"11111111-1111-1111-1111-111111111111\",\"status\":7}}"})
    void onlyAMissingOrInvalidUserIdOrStatusMakesItUnreadable(String value) {
        assertThat(parser.parse(value)).isInstanceOf(Unreadable.class);
    }

    @Test
    void theReasonNeverCarriesTheMessageContent() {
        Unreadable unreadable = (Unreadable) parser.parse("{\"payload\":{\"email\":\"secreto@hilos.pe\",\"status\":\"INACTIVE\"}}");
        assertThat(unreadable.reason()).doesNotContain("secreto");
    }
}
