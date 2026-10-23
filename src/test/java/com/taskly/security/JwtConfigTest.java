package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class JwtConfigTest {

    private static JwtProperties props(String secret) {
        return new JwtProperties(secret, "taskly", Duration.ofMinutes(15), Duration.ofDays(30));
    }

    @Test
    void rejectsMissingOrShortSecret() {
        // "${JWT_SECRET}" là chuỗi còn lại khi biến môi trường chưa được khai báo.
        assertThatThrownBy(() -> new JwtConfig().jwtSecretKey(props("${JWT_SECRET}")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
        assertThatThrownBy(() -> new JwtConfig().jwtSecretKey(props(null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsSecretOfAtLeast32Bytes() {
        SecretKey key = new JwtConfig().jwtSecretKey(props("0123456789abcdef0123456789abcdef"));
        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(key.getEncoded()).hasSize(32);
    }
}
