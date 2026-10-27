package com.taskly.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** Vector chuẩn RFC 6238 phụ lục B (SHA-1, secret ASCII "12345678901234567890"), lấy 6 số cuối của mã 8 số. */
class TotpTestCodesTest {

    private static final String RFC_SECRET_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    void matchesRfc6238Vectors() {
        assertThat(TotpTestCodes.code(RFC_SECRET_BASE32, Instant.ofEpochSecond(59))).isEqualTo("287082");
        assertThat(TotpTestCodes.code(RFC_SECRET_BASE32, Instant.ofEpochSecond(1111111109L))).isEqualTo("081804");
        assertThat(TotpTestCodes.code(RFC_SECRET_BASE32, Instant.ofEpochSecond(1111111111L))).isEqualTo("050471");
        assertThat(TotpTestCodes.code(RFC_SECRET_BASE32, Instant.ofEpochSecond(1234567890L))).isEqualTo("005924");
        assertThat(TotpTestCodes.code(RFC_SECRET_BASE32, Instant.ofEpochSecond(2000000000L))).isEqualTo("279037");
    }

    @Test
    void acceptsLowercaseAndPadding() {
        assertThat(TotpTestCodes.code(RFC_SECRET_BASE32.toLowerCase(Locale.ROOT) + "====", Instant.ofEpochSecond(59)))
                .isEqualTo("287082");
    }
}
