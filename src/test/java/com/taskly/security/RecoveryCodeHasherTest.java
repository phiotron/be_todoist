package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit thuần: băm mã khôi phục bằng HMAC-SHA256 với pepper dẫn xuất từ khoá TOTP (không còn SHA-256 trần, không muối). */
class RecoveryCodeHasherTest {

    private static final String TEST_KEY = "dGVzdC1vbmx5LXRvdHAta2V5LW5vdC1mb3ItcHJvZCE=";
    private static final String OTHER_TEST_KEY = "YW5vdGhlci10ZXN0LW9ubHktdG90cC1rZXktMzJieSE=";
    private static final UUID USER = UUID.fromString("3f2b8c1e-7d4a-4b6e-9c1d-2a5e8f0b7c3d");
    private static final UUID OTHER_USER = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d");

    private final RecoveryCodeHasher hasher = new RecoveryCodeHasher(new SecretCipher(TEST_KEY));

    @Test
    void hash_isDeterministicLowercase64Hex() {
        assertThat(hasher.hash(USER, "abcdef23")).hasSize(64).matches("[0-9a-f]{64}")
                .isEqualTo(hasher.hash(USER, "abcdef23"));
    }

    @Test
    void hash_isNotThePlainSha256_soAStolenTableCannotBeBruteForcedWithoutTheServerKey() {
        assertThat(hasher.hash(USER, "abcdef23")).isNotEqualTo(OpaqueTokens.sha256Hex("abcdef23"))
                .isNotEqualTo(OpaqueTokens.sha256Hex(USER + "abcdef23"))
                .isNotEqualTo(OpaqueTokens.sha256Hex(USER + ":abcdef23"));
    }

    @Test
    void hash_dependsOnTheUser_theCode_andTheServerKey() {
        String base = hasher.hash(USER, "abcdef23");

        assertThat(hasher.hash(OTHER_USER, "abcdef23")).as("cùng mã ở user khác → hash khác").isNotEqualTo(base);
        assertThat(hasher.hash(USER, "abcdef24")).isNotEqualTo(base);
        assertThat(new RecoveryCodeHasher(new SecretCipher(OTHER_TEST_KEY)).hash(USER, "abcdef23"))
                .as("khoá TOTP khác → pepper khác").isNotEqualTo(base);
    }

    @Test
    void secretCipherDerivesDistinctSubKeysPerLabel_withoutExposingTheKey() {
        SecretCipher cipher = new SecretCipher(TEST_KEY);

        assertThat(cipher.deriveKey("recovery-code")).hasSize(32).isEqualTo(cipher.deriveKey("recovery-code"))
                .isNotEqualTo(cipher.deriveKey("other-label"))
                .isNotEqualTo(java.util.Base64.getDecoder().decode(TEST_KEY));
        assertThat(new SecretCipher(OTHER_TEST_KEY).deriveKey("recovery-code"))
                .isNotEqualTo(cipher.deriveKey("recovery-code"));
    }
}
