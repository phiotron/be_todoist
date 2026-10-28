package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Chữ ký HMAC-SHA256 của webhook email inbound (M13a): `v1=` + hex(HMAC(secret, timestamp + "." + body)). */
class InboundSignatureVerifierTest {

    private static final String SECRET = "unit-test-secret-not-real";
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");
    private static final byte[] BODY = "{\"messageId\":\"<a@b>\"}".getBytes(StandardCharsets.UTF_8);

    private final InboundSignatureVerifier verifier =
            new InboundSignatureVerifier(SECRET, Duration.ofSeconds(300), Clock.fixed(NOW, ZoneOffset.UTC));

    private static String ts(Instant instant) {
        return Long.toString(instant.getEpochSecond());
    }

    private static void assertRejected(Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ex = (ApiException) e;
                    assertThat(ex.getStatusCode().value()).isEqualTo(401);
                    assertThat(ex.getErrorMessage()).isEqualTo(ErrorMessage.EMAIL_SIGNATURE_INVALID);
                });
    }

    @Test
    void acceptsAValidSignature() {
        String signature = InboundSignatureVerifier.sign(SECRET, NOW.getEpochSecond(), BODY);
        assertThat(signature).matches("v1=[0-9a-f]{64}");
        assertThatNoException().isThrownBy(() -> verifier.verify(ts(NOW), signature, BODY));
    }

    @Test
    void acceptsTimestampsInsideTheTolerance() {
        Instant past = NOW.minusSeconds(300);
        assertThatNoException().isThrownBy(() ->
                verifier.verify(ts(past), InboundSignatureVerifier.sign(SECRET, past.getEpochSecond(), BODY), BODY));
    }

    @Test
    void rejectsWrongSecretTamperedBodyOrOtherTimestamp() {
        String signature = InboundSignatureVerifier.sign(SECRET, NOW.getEpochSecond(), BODY);
        assertRejected(() -> verifier.verify(ts(NOW),
                InboundSignatureVerifier.sign("other-secret", NOW.getEpochSecond(), BODY), BODY));
        assertRejected(() -> verifier.verify(ts(NOW), signature, "{}".getBytes(StandardCharsets.UTF_8)));
        assertRejected(() -> verifier.verify(ts(NOW.plusSeconds(1)), signature, BODY));
    }

    @Test
    void rejectsStaleOrFutureTimestamps() {
        Instant stale = NOW.minusSeconds(301);
        Instant future = NOW.plusSeconds(301);
        assertRejected(() -> verifier.verify(ts(stale),
                InboundSignatureVerifier.sign(SECRET, stale.getEpochSecond(), BODY), BODY));
        assertRejected(() -> verifier.verify(ts(future),
                InboundSignatureVerifier.sign(SECRET, future.getEpochSecond(), BODY), BODY));
    }

    @Test
    void rejectsMissingOrMalformedHeaders() {
        String hex = InboundSignatureVerifier.sign(SECRET, NOW.getEpochSecond(), BODY).substring(3);
        assertRejected(() -> verifier.verify(null, "v1=" + hex, BODY));
        assertRejected(() -> verifier.verify(ts(NOW), null, BODY));
        assertRejected(() -> verifier.verify("abc", "v1=" + hex, BODY));
        assertRejected(() -> verifier.verify(ts(NOW), hex, BODY));
        assertRejected(() -> verifier.verify(ts(NOW), "v1=zz", BODY));
    }

    @Test
    void blankSecretMeansNotConfigured_andRejectsEverything() {
        InboundSignatureVerifier unconfigured =
                new InboundSignatureVerifier("  ", Duration.ofSeconds(300), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(unconfigured.configured()).isFalse();
        assertThat(verifier.configured()).isTrue();
        assertRejected(() -> unconfigured.verify(ts(NOW), InboundSignatureVerifier.sign("  ", NOW.getEpochSecond(), BODY),
                BODY));
    }

    @Test
    void toStringHidesTheSecret() {
        assertThat(verifier.toString()).doesNotContain(SECRET);
    }
}
