package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.security.GoogleIdentity;
import org.junit.jupiter.api.Test;

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
}
