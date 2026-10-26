package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class GooglePropertiesTest {

    @Test
    void stripsAndDropsBlankClientIds() {
        GoogleProperties properties = new GoogleProperties(Arrays.asList(" a.apps ", "", null, "b.apps"), null);

        assertThat(properties.clientIds()).containsExactly("a.apps", "b.apps");
    }

    @Test
    void defaultsToNoClientIdsAndGoogleJwkSetUri() {
        GoogleProperties properties = new GoogleProperties(null, "  ");

        assertThat(properties.clientIds()).isEmpty();
        assertThat(properties.jwkSetUri()).isEqualTo("https://www.googleapis.com/oauth2/v3/certs");
        assertThat(new GoogleProperties(List.of(), "https://example.test/certs").jwkSetUri())
                .isEqualTo("https://example.test/certs");
    }
}
