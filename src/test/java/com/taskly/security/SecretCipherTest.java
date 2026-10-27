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
    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    private final SecretCipher cipher = new SecretCipher(TEST_KEY);

    @Test
    void encryptThenDecrypt_roundTrips_withFreshIvEachTime() {
        String first = cipher.encrypt(SECRET);
        String second = cipher.encrypt(SECRET);

        assertThat(cipher.decrypt(first)).isEqualTo(SECRET);
        assertThat(cipher.decrypt(second)).isEqualTo(SECRET);
        assertThat(first).isNotEqualTo(second);
        assertThat(Base64.getDecoder().decode(first)).hasSize(12 + SECRET.length + 16);
        assertThat(first).doesNotContain(Base64.getEncoder().encodeToString(SECRET));
    }

    @Test
    void tamperedCiphertext_isRejectedWithoutLeakingData() {
        byte[] sealed = Base64.getDecoder().decode(cipher.encrypt(SECRET));
        sealed[sealed.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(sealed);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(tampered);
        assertThatThrownBy(() -> cipher.decrypt("khong-phai-base64!!")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.decrypt("AAAA")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anotherKey_cannotDecrypt() {
        String encrypted = cipher.encrypt(SECRET);

        assertThatThrownBy(() -> new SecretCipher(OTHER_TEST_KEY).decrypt(encrypted))
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
