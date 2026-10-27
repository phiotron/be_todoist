package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Unit thuần. Hai khoá dưới là giá trị GIẢ cho test (base64 của chuỗi ASCII 32 byte), không phải bí mật thật. */
class SecretCipherTest {

    private static final String TEST_KEY = "dGVzdC1vbmx5LXRvdHAta2V5LW5vdC1mb3ItcHJvZCE=";
    private static final String OTHER_TEST_KEY = "YW5vdGhlci10ZXN0LW9ubHktdG90cC1rZXktMzJieSE=";
    private static final java.util.UUID USER = java.util.UUID.fromString("3f2b8c1e-7d4a-4b6e-9c1d-2a5e8f0b7c3d");
    private static final java.util.UUID OTHER_USER = java.util.UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    private final SecretCipher cipher = new SecretCipher(TEST_KEY);

    @Test
    void encryptThenDecrypt_roundTrips_withFreshIvEachTime() {
        String first = cipher.encrypt(USER, SECRET);
        String second = cipher.encrypt(USER, SECRET);

        assertThat(cipher.decrypt(USER, first)).isEqualTo(SECRET);
        assertThat(cipher.decrypt(USER, second)).isEqualTo(SECRET);
        assertThat(first).isNotEqualTo(second);
        assertThat(Base64.getDecoder().decode(first.substring("v1:".length()))).hasSize(12 + SECRET.length + 16);
        assertThat(first).doesNotContain(Base64.getEncoder().encodeToString(SECRET));
    }

    @Test
    void tamperedCiphertext_isRejectedWithoutLeakingData() {
        byte[] sealed = Base64.getDecoder().decode(cipher.encrypt(USER, SECRET).substring("v1:".length()));
        sealed[sealed.length - 1] ^= 0x01;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(sealed);

        assertThatThrownBy(() -> cipher.decrypt(USER, tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(tampered);
        assertThatThrownBy(() -> cipher.decrypt(USER, "khong-phai-base64!!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(USER, "AAAA")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void storedValue_carriesAVersionPrefix() {
        assertThat(cipher.encrypt(USER, SECRET)).startsWith("v1:");
        String withoutPrefix = cipher.encrypt(USER, SECRET).substring("v1:".length());
        assertThatThrownBy(() -> cipher.decrypt(USER, withoutPrefix)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt(USER, "v2:" + withoutPrefix)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ciphertextCopiedToAnotherUser_cannotBeDecrypted_becauseTheUserIdIsAuthenticated() {
        String forUser = cipher.encrypt(USER, SECRET);

        assertThat(cipher.decrypt(USER, forUser)).isEqualTo(SECRET);
        assertThatThrownBy(() -> cipher.decrypt(OTHER_USER, forUser)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherKey_cannotDecrypt() {
        String encrypted = cipher.encrypt(USER, SECRET);

        assertThatThrownBy(() -> new SecretCipher(OTHER_TEST_KEY).decrypt(USER, encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingOrInvalidKey_failsFast_namingTheVariableButNotTheValue() {
        String sixteenBytes = Base64.getEncoder().encodeToString("0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        for (String bad : new String[] {null, "", "   ", "${TOTP_ENCRYPTION_KEY}", sixteenBytes, "khong-phai-base64!!"}) {
            assertThatThrownBy(() -> new SecretCipher(bad))
                    .as("khoá %s", bad == null ? "null" : "đã che")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TOTP_ENCRYPTION_KEY")
                    .message().doesNotContain(sixteenBytes).doesNotContain("${");
        }
    }

    @Test
    void toString_doesNotLeakKey() {
        assertThat(cipher.toString()).isEqualTo("SecretCipher[key=***]").doesNotContain(TEST_KEY);
    }
}
