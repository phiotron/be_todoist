package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OpaqueTokensTest {

    @Test
    void generateReturns43CharBase64UrlWithoutPadding() {
        assertThat(OpaqueTokens.generate()).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void generateEncodes32RandomBytes() {
        assertThat(Base64.getUrlDecoder().decode(OpaqueTokens.generate())).hasSize(32);
    }

    @Test
    void generateIsUnique() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            tokens.add(OpaqueTokens.generate());
        }
        assertThat(tokens).hasSize(1000);
    }

    @Test
    void sha256HexMatchesKnownVector() {
        assertThat(OpaqueTokens.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sha256HexIsDeterministicLowercase64Hex() {
        String token = OpaqueTokens.generate();
        assertThat(OpaqueTokens.sha256Hex(token))
                .matches("[0-9a-f]{64}")
                .isEqualTo(OpaqueTokens.sha256Hex(token))
                .isNotEqualTo(token);
    }
}
