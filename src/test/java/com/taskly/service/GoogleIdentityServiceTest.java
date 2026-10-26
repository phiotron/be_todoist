package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;
import com.taskly.security.GoogleIdTokenVerifier;
import com.taskly.security.GoogleIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

/** Unit test thuần cho phần logic không cần DB của GoogleIdentityService. */
class GoogleIdentityServiceTest {

    private static GoogleIdentity google(String email, String name) {
        return new GoogleIdentity("sub", email, true, name);
    }

    @Test
    void displayName_prefersGoogleNameThenEmailLocalPart() {
        assertThat(GoogleIdentityService.displayName(google("an@gmail.com", "  Bình  "))).isEqualTo("Bình");
        assertThat(GoogleIdentityService.displayName(google("an.nguyen@gmail.com", null))).isEqualTo("an.nguyen");
        assertThat(GoogleIdentityService.displayName(google("an@gmail.com", "   "))).isEqualTo("an");
    }

    @Test
    void displayName_isCappedAt100CodePointsWithoutSplittingSurrogates() {
        String capped = GoogleIdentityService.displayName(google("an@gmail.com", "😀".repeat(150)));

        assertThat(capped.codePointCount(0, capped.length())).isEqualTo(100);
        assertThat(capped).isEqualTo("😀".repeat(100));
    }

    @Test
    void googleKeysUnreachable_returns503AndTouchesNothing() {
        GoogleIdTokenVerifier unreachable = new GoogleIdTokenVerifier(token -> {
            throw new JwtException("Couldn't retrieve remote JWK set");
        });
        // userService / authService / linkTokens = null: chứng minh không có gì được đọc hay ghi.
        GoogleIdentityService service = new GoogleIdentityService(unreachable, null, null, null);

        assertThatThrownBy(() -> service.signIn("any.id.token"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(503);
                    assertThat(ex.getBody().getDetail())
                            .isEqualTo("Không kết nối được tới Google, vui lòng thử lại sau");
                });
    }
}
