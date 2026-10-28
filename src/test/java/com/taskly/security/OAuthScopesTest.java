package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.common.error.ApiException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Bảng scope x method x đường dẫn của token OAuth (plan M13c, F3). */
class OAuthScopesTest {

    @ParameterizedTest(name = "{0} {1} {2} -> {3}")
    @CsvSource(delimiter = '|', value = {
            // scope | method | path | allowed
            "data:read|GET|/api/v1/projects|true",
            "data:read|GET|/api/v1/tasks/abc|true",
            "data:read|GET|/api/v1/me|true",
            "data:read|POST|/api/v1/tasks|false",
            "data:read|PATCH|/api/v1/tasks/abc|false",
            "data:read|DELETE|/api/v1/tasks/abc|false",
            "data:read_write|POST|/api/v1/tasks|true",
            "data:read_write|PATCH|/api/v1/tasks/abc|true",
            "data:read_write|POST|/api/v1/projects|true",
            "data:read_write|POST|/api/v1/tasks/abc/close|true",
            "data:read_write|GET|/api/v1/labels|true",
            "data:read_write|DELETE|/api/v1/tasks/abc|false",
            "data:read_write|DELETE|/api/v1/projects/abc|false",
            "data:delete|DELETE|/api/v1/tasks/abc|true",
            "data:delete|DELETE|/api/v1/labels/abc|true",
            "data:delete|DELETE|/api/v1/filters/abc|true",
            "data:delete|DELETE|/api/v1/projects/abc|false",
            "data:delete|DELETE|/api/v1/projects/abc/sections/def|true",
            "data:delete|POST|/api/v1/tasks|true",
            "project:delete|DELETE|/api/v1/projects/abc|true",
            "project:delete|GET|/api/v1/projects|false",
            "project:delete|DELETE|/api/v1/tasks/abc|false",
            "task:add|POST|/api/v1/tasks|true",
            "task:add|GET|/api/v1/tasks|false",
            "task:add|POST|/api/v1/projects|false",
            "task:add|PATCH|/api/v1/tasks/abc|false",
            "task:add|GET|/api/v1/me|false",
            // endpoint không mở cho OAuth, kể cả khi scope rộng nhất
            "data:delete|GET|/api/v1/me/settings|false",
            "data:delete|PATCH|/api/v1/me|false",
            "data:delete|GET|/api/v1/developer/apps|false",
            "data:delete|POST|/api/v1/oauth/authorize|false",
            "data:delete|GET|/api/v1/me/authorized-apps|false",
            "data:delete|POST|/api/v1/me/push-tokens|false",
            "data:delete|POST|/api/v1/auth/logout|false",
            "data:delete|GET|/api/v1/view-options|false",
    })
    void allowsTable(String scope, String method, String path, boolean expected) {
        assertThat(OAuthScopes.allows(Set.of(scope), method, path)).isEqualTo(expected);
    }

    @Test
    void parseRequestedAcceptsCommaOrSpaceAndDeduplicates() {
        assertThat(OAuthScopes.parseRequested("data:read, task:add data:read"))
                .containsExactly("data:read", "task:add");
    }

    @Test
    void parseRequestedRejectsEmptyOrUnknown() {
        assertThatThrownBy(() -> OAuthScopes.parseRequested("")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> OAuthScopes.parseRequested(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> OAuthScopes.parseRequested("data:read admin")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> OAuthScopes.parseRequested("backups:read")).isInstanceOf(ApiException.class);
    }

    @Test
    void storedFormRoundTrips() {
        Set<String> scopes = OAuthScopes.parseRequested("data:read,task:add");
        assertThat(OAuthScopes.fromStored(OAuthScopes.toStored(scopes))).containsExactlyElementsOf(scopes);
    }
}
