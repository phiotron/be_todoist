package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.support.GoogleTestTokens;
import com.taskly.support.MutableClock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

/** Unit test thuần (không Spring, không mạng): verifier dùng khoá công khai test thay cho JWKS của Google. */
class GoogleIdTokenVerifierTest {

    private final MutableClock clock = new MutableClock();
    private final GoogleTestTokens tokens = new GoogleTestTokens(clock);
    private final GoogleIdTokenVerifier verifier = GoogleIdTokenVerifier.withPublicKey(
            tokens.trustedPublicKey(), new GoogleProperties(GoogleTestTokens.CLIENT_IDS, null), clock);

    @Test
    void validToken_returnsIdentityWithNormalizedEmail() {
        GoogleTestTokens.IdToken token = tokens.idToken().email("  Mixed.Case@Gmail.com ").name("Nguyễn Văn A");

        GoogleIdentity identity = verifier.verify(token.sign());

        assertThat(identity.subject()).isEqualTo(token.getSubject());
        assertThat(identity.email()).isEqualTo("mixed.case@gmail.com");
        assertThat(identity.emailVerified()).isTrue();
        assertThat(identity.name()).isEqualTo("Nguyễn Văn A");
    }

    @Test
    void acceptsBothGoogleIssuerFormsAndAnyConfiguredClientId() {
        assertThat(verifier.verify(tokens.idToken().issuer("accounts.google.com").sign())).isNotNull();
        assertThat(verifier.verify(tokens.idToken().issuer("https://accounts.google.com").sign())).isNotNull();
        assertThat(verifier.verify(tokens.idToken().audience(GoogleTestTokens.ANDROID_CLIENT_ID).sign())).isNotNull();
    }

    @Test
    void emailVerified_acceptsBooleanOrStringTrueOnly() {
        assertThat(verifier.verify(tokens.idToken().emailVerified(true).sign()).emailVerified()).isTrue();
        assertThat(verifier.verify(tokens.idToken().emailVerified("true").sign()).emailVerified()).isTrue();
        assertThat(verifier.verify(tokens.idToken().emailVerified(false).sign()).emailVerified()).isFalse();
        assertThat(verifier.verify(tokens.idToken().emailVerified("false").sign()).emailVerified()).isFalse();
        assertThat(verifier.verify(tokens.idToken().emailVerified(null).sign()).emailVerified()).isFalse();
    }

    @Test
    void rejectsEveryInvalidVariantWithoutEchoingTheToken() {
        Map<String, String> invalid = Map.of(
                "khoá lạ", tokens.idToken().signedByUntrustedKey().sign(),
                "aud của app khác", tokens.idToken().audience("other-app.apps.googleusercontent.com").sign(),
                "iss không phải Google", tokens.idToken().issuer("https://accounts.example.com").sign(),
                "đã hết hạn quá dung sai 60 giây", tokens.idToken().lifetime(Duration.ofMinutes(-5)).sign(),
                "thiếu exp", tokens.idToken().withoutExpiry().sign(),
                "thiếu email", tokens.idToken().email(null).sign(),
                "sub rỗng", tokens.idToken().subject(" ").sign(),
                "email quá 254 ký tự", tokens.idToken().email("a".repeat(250) + "@gmail.com").sign(),
                "không phải JWT", "not-a-jwt");

        invalid.forEach((label, idToken) -> assertThatThrownBy(() -> verifier.verify(idToken))
                .as(label)
                .isInstanceOf(InvalidGoogleTokenException.class)
                .hasMessageNotContaining(idToken));
    }

    @Test
    void tokenExpiresWhenClockPassesExpPlusSkew() {
        String idToken = tokens.idToken().lifetime(Duration.ofMinutes(10)).sign();
        assertThat(verifier.verify(idToken)).isNotNull();

        clock.advance(Duration.ofMinutes(12));

        assertThatThrownBy(() -> verifier.verify(idToken)).isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void emptyClientIdList_rejectsEverything() {
        GoogleIdTokenVerifier unconfigured = GoogleIdTokenVerifier.withPublicKey(
                tokens.trustedPublicKey(), new GoogleProperties(List.of(), null), clock);

        assertThatThrownBy(() -> unconfigured.verify(tokens.idToken().sign()))
                .isInstanceOf(InvalidGoogleTokenException.class);
    }

    @Test
    void keySourceFailure_isUnavailableNotInvalid() {
        GoogleIdTokenVerifier unreachable = new GoogleIdTokenVerifier(token -> {
            throw new JwtException("Couldn't retrieve remote JWK set");
        });

        assertThatThrownBy(() -> unreachable.verify("a.b.c")).isInstanceOf(GoogleUnavailableException.class);
    }
}
