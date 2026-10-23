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
    void errorMessageNeverContainsSecretValue() {
        String shortSecret = "0123456789abcdef0123456789abcde"; // 31 byte
        assertThatThrownBy(() -> new JwtConfig().jwtSecretKey(props(shortSecret)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET")
                .message().doesNotContain(shortSecret);
        assertThatThrownBy(() -> new JwtConfig().jwtSecretKey(props("${JWT_SECRET}")))
                .message().doesNotContain("${");
    }

    @Test
    void countsUtf8BytesNotCharacters() {
        // "đ" = 2 byte UTF-8: 16 ký tự = 32 byte (đủ), 15 ký tự = 30 byte (thiếu).
        SecretKey key = new JwtConfig().jwtSecretKey(props("đ".repeat(16)));
        assertThat(key.getEncoded()).hasSize(32);
        assertThatThrownBy(() -> new JwtConfig().jwtSecretKey(props("đ".repeat(15))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void acceptsSecretOfAtLeast32Bytes() {
        SecretKey key = new JwtConfig().jwtSecretKey(props("0123456789abcdef0123456789abcdef"));
        assertThat(key.getAlgorithm()).isEqualTo("HmacSHA256");
        assertThat(key.getEncoded()).hasSize(32);
    }

    @Test
    void propertiesToStringDoesNotLeakSecret() {
        String secret = "0123456789abcdef0123456789abcdef";
        assertThat(props(secret).toString())
                .doesNotContain(secret)
                .isEqualTo("JwtProperties[secret=***, issuer=taskly, accessTtl=PT15M, refreshTtl=PT720H]");
    }
}
