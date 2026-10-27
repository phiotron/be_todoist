package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Unit thuần: vector chuẩn RFC 6238 (SHA-1), cửa sổ ±1 bước, chống dùng lại, base32, otpauth URI. */
class TotpCodecTest {

    /** Secret của phụ lục B RFC 6238 (bản SHA-1): chuỗi ASCII "12345678901234567890". */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final String RFC_SECRET_BASE32 = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @ParameterizedTest
    @CsvSource({
            "59, 94287082",
            "1111111109, 07081804",
            "1111111111, 14050471",
            "1234567890, 89005924",
            "2000000000, 69279037",
            "20000000000, 65353130"})
    void rfc6238Sha1Vectors_eightDigits(long epochSeconds, String expected) {
        long step = TotpCodec.step(Instant.ofEpochSecond(epochSeconds));

        assertThat(TotpCodec.code(RFC_SECRET, step, 8)).isEqualTo(expected);
    }

    @Test
    void sixDigitCodeIsTheLastSixDigitsZeroPadded() {
        assertThat(TotpCodec.code(RFC_SECRET, TotpCodec.step(Instant.ofEpochSecond(59)))).isEqualTo("287082");
        assertThat(TotpCodec.code(RFC_SECRET, TotpCodec.step(Instant.ofEpochSecond(1111111109)))).isEqualTo("081804");
        assertThat(TotpCodec.code(RFC_SECRET, TotpCodec.step(Instant.ofEpochSecond(1234567890)))).isEqualTo("005924");
    }

    @Test
    void base32_encodesRfcSecretAndRoundTrips() {
        assertThat(TotpCodec.base32(RFC_SECRET)).isEqualTo(RFC_SECRET_BASE32);
        assertThat(TotpCodec.fromBase32(RFC_SECRET_BASE32)).isEqualTo(RFC_SECRET);
        byte[] random = TotpCodec.newSecret();
        assertThat(TotpCodec.fromBase32(TotpCodec.base32(random))).isEqualTo(random);
    }

    @Test
    void newSecret_is20RandomBytes() {
        byte[] first = TotpCodec.newSecret();
        byte[] second = TotpCodec.newSecret();

        assertThat(first).hasSize(TotpCodec.SECRET_BYTES).hasSize(20);
        assertThat(first).isNotEqualTo(second);
        assertThat(TotpCodec.base32(first)).hasSize(32).matches("[A-Z2-7]{32}");
    }

    @Test
    void fromBase32_rejectsCharactersOutsideTheAlphabet() {
        assertThatThrownBy(() -> TotpCodec.fromBase32("GEZD1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TotpCodec.fromBase32("gezdgnbv"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TotpCodec.fromBase32(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void matchingStep_acceptsCurrentPreviousAndNextStepOnly() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long current = TotpCodec.step(now);

        for (long offset = -1; offset <= 1; offset++) {
            String code = TotpCodec.code(RFC_SECRET, current + offset);
            assertThat(TotpCodec.matchingStep(RFC_SECRET, code, now, null)).hasValue(current + offset);
        }
        assertThat(TotpCodec.matchingStep(RFC_SECRET, TotpCodec.code(RFC_SECRET, current - 2), now, null)).isEmpty();
        assertThat(TotpCodec.matchingStep(RFC_SECRET, TotpCodec.code(RFC_SECRET, current + 2), now, null)).isEmpty();
    }

    @Test
    void matchingStep_rejectsStepsAlreadyUsed_butAcceptsALaterStep() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);
        long current = TotpCodec.step(now);
        String currentCode = TotpCodec.code(RFC_SECRET, current);

        assertThat(TotpCodec.matchingStep(RFC_SECRET, currentCode, now, current)).as("dùng lại cùng bước").isEmpty();
        assertThat(TotpCodec.matchingStep(RFC_SECRET, TotpCodec.code(RFC_SECRET, current - 1), now, current))
                .as("bước cũ hơn bước đã dùng").isEmpty();
        assertThat(TotpCodec.matchingStep(RFC_SECRET, TotpCodec.code(RFC_SECRET, current + 1), now, current))
                .hasValue(current + 1);
    }

    @Test
    void matchingStep_rejectsMalformedCodes() {
        Instant now = Instant.ofEpochSecond(1_700_000_000L);

        for (String code : new String[] {null, "", "12345", "1234567", "abcdef", "12 345", "１２３４５６"}) {
            assertThat(TotpCodec.matchingStep(RFC_SECRET, code, now, null)).as("mã %s", code).isEmpty();
        }
    }

    @Test
    void otpauthUri_hasIssuerLabelSecretAndParameters() {
        assertThat(TotpCodec.otpauthUri("Taskly", "an@example.com", RFC_SECRET_BASE32)).isEqualTo(
                "otpauth://totp/Taskly:an%40example.com?secret=" + RFC_SECRET_BASE32
                        + "&issuer=Taskly&algorithm=SHA1&digits=6&period=30");
    }
}
