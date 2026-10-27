package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

class CurrentUserTest {

    private static Jwt jwtWithSubject(String subject) {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(subject).build();
    }

    @Test
    void returnsUuidSubject() {
        UUID id = UUID.randomUUID();
        assertThat(CurrentUser.id(jwtWithSubject(id.toString()))).isEqualTo(id);
    }

    @Test
    void nonUuidSubject_isTreatedAsInvalidToken() {
        // Trước M1: UUID.fromString ném IllegalArgumentException → 500. Nay là lỗi xác thực → 401.
        assertThatThrownBy(() -> CurrentUser.id(jwtWithSubject("not-a-uuid")))
                .isInstanceOf(InvalidBearerTokenException.class);
    }
}
