package com.taskly.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.security.TotpCodec;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Unit thuần: mã "sai" của test không bao giờ trùng mã hợp lệ trong cửa sổ ±1 bước (tránh test chớp nhoáng 1/10^6 / 3). */
class TwoFactorStepsTest {

    @Test
    void wrongCode_isSixDigits_andNeverEqualsAnyCodeInTheAcceptedWindow() {
        byte[] secret = TotpCodec.newSecret();
        String base32 = TotpCodec.base32(secret);
        MutableClock clock = new MutableClock();

        for (int i = 0; i < 200; i++) {
            clock.advance(java.time.Duration.ofSeconds(30));
            String wrong = TwoFactorSteps.wrongCode(base32, clock);
            long current = TotpCodec.step(Instant.now(clock));

            assertThat(wrong).matches("[0-9]{6}");
            for (long step = current - 2; step <= current + 2; step++) {
                assertThat(wrong).isNotEqualTo(TotpCodec.code(secret, step));
            }
        }
    }

    @Test
    void wrongCode_skipsTheFirstCandidateWhenItCollidesWithTheCurrentCode() {
        // Secret RFC 6238 tại t=59 s → bước 1 có mã 287082; mã sai trả về phải khác mọi mã trong cửa sổ.
        byte[] secret = "12345678901234567890".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        MutableClock clock = new MutableClock() {
            @Override
            public Instant instant() {
                return Instant.ofEpochSecond(59);
            }
        };

        assertThat(TwoFactorSteps.wrongCode(TotpCodec.base32(secret), clock)).isNotEqualTo("287082");
    }
}
