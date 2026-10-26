package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.GoogleIntegrationTestBase;
import com.taskly.security.OpaqueTokens;
import com.taskly.support.GoogleTestTokens;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** POST /api/v1/auth/google — bảng "Đăng nhập Google & liên kết tài khoản" (spec 12.1). */
class GoogleSignInApiTest extends GoogleIntegrationTestBase {

    // Tình huống 1: email Google chưa có tài khoản → tạo tài khoản không mật khẩu + workspace + Inbox + liên kết.
    @Test
    void newGoogleEmail_createsPasswordlessAccountWithWorkspaceInboxAndIdentity() throws Exception {
        GoogleTestTokens.IdToken token = googleTokens.idToken().name("  Nguyễn Văn Google  ");

        String json = body(postGoogleSignIn(token.sign())
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken", matchesPattern("[A-Za-z0-9_-]{43}")))
                .andExpect(jsonPath("$.user.email").value(token.getEmail()))
                .andExpect(jsonPath("$.user.displayName").value("Nguyễn Văn Google"))
                .andExpect(jsonPath("$.user.hasPassword").value(false))
                .andExpect(jsonPath("$.user.linkedProviders", contains("GOOGLE")))
                .andReturn());
        UUID userId = UUID.fromString(JsonPath.read(json, "$.user.id"));

        assertThat(jdbcTemplate.queryForObject(
                "select password_hash from users where id = ?", String.class, userId)).isNull();
        Map<String, Object> identity = jdbcTemplate.queryForMap(
                "select user_id, provider, email from user_identities where subject = ?", token.getSubject());
        assertThat(identity).containsEntry("user_id", userId)
                .containsEntry("provider", "GOOGLE")
                .containsEntry("email", token.getEmail());
        Map<String, Object> workspace = jdbcTemplate.queryForMap(
                "select id, type from workspaces where owner_id = ?", userId);
        assertThat(workspace).containsEntry("type", "PERSONAL");
        assertThat(jdbcTemplate.queryForObject(
                "select role from workspace_members where workspace_id = ? and user_id = ?",
                String.class, workspace.get("id"), userId)).isEqualTo("ADMIN");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from projects where workspace_id = ? and is_inbox",
                Integer.class, workspace.get("id"))).isEqualTo(1);
        String refreshToken = JsonPath.read(json, "$.refreshToken");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where user_id = ? and token_hash = ?",
                Integer.class, userId, OpaqueTokens.sha256Hex(refreshToken))).isEqualTo(1);
        String accessToken = JsonPath.read(json, "$.accessToken");
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId.toString()));
    }

    @Test
    void googleEmailIsNormalizedBeforeStoring() throws Exception {
        String raw = "  Mixed.Case-" + UUID.randomUUID() + "@Gmail.com ";

        postGoogleSignIn(googleTokens.idToken().email(raw).sign())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(raw.strip().toLowerCase(Locale.ROOT)));
    }

    @Test
    void missingGoogleName_usesEmailLocalPartAsDisplayName() throws Exception {
        String localPart = "ten-rieng-" + UUID.randomUUID();

        postGoogleSignIn(googleTokens.idToken().name(null).email(localPart + "@gmail.com").sign())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.displayName").value(localPart));
    }

    // Tình huống 4: sub đã có trong user_identities → đăng nhập đúng tài khoản đó (dù email Google đã đổi).
    @Test
    void linkedSubject_logsIntoSameAccountEvenIfGoogleEmailChanged() throws Exception {
        GoogleTestTokens.IdToken first = googleTokens.idToken();
        GoogleUser user = signInWithGoogle(first);
        GoogleTestTokens.IdToken again = googleTokens.idToken().subject(first.getSubject()); // email mới ngẫu nhiên

        postGoogleSignIn(again.sign())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.id().toString()))
                .andExpect(jsonPath("$.user.email").value(user.email()));

        assertThat(countIdentities(first.getSubject())).isEqualTo(1);
        assertThat(countUsersByEmail(again.getEmail())).isZero();
    }

    @Test
    void linkedSubject_logsInEvenIfEmailNoLongerVerified() throws Exception {
        GoogleTestTokens.IdToken first = googleTokens.idToken();
        GoogleUser user = signInWithGoogle(first);

        postGoogleSignIn(googleTokens.idToken().subject(first.getSubject()).emailVerified(false).sign())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.id().toString()));
    }

    // Tình huống 6: token sai / hết hạn / sai aud → 401, không tạo tài khoản, không đổi liên kết.
    @Test
    void invalidIdTokens_return401AndChangeNothing() throws Exception {
        Map<String, GoogleTestTokens.IdToken> cases = new LinkedHashMap<>();
        cases.put("chữ ký bằng khoá lạ", googleTokens.idToken().signedByUntrustedKey());
        cases.put("aud của app khác", googleTokens.idToken().audience("other-app.apps.googleusercontent.com"));
        cases.put("iss không phải Google", googleTokens.idToken().issuer("https://accounts.example.com"));
        cases.put("đã hết hạn", googleTokens.idToken().lifetime(Duration.ofMinutes(-5)));
        cases.put("thiếu exp", googleTokens.idToken().withoutExpiry());
        cases.put("thiếu email", googleTokens.idToken().email(null));

        for (Map.Entry<String, GoogleTestTokens.IdToken> testCase : cases.entrySet()) {
            GoogleTestTokens.IdToken token = testCase.getValue();
            try {
                postGoogleSignIn(token.sign())
                        .andExpect(status().isUnauthorized())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.detail").value("Đăng nhập Google không thành công, vui lòng thử lại"))
                        .andExpect(jsonPath("$.accessToken").doesNotExist());
            } catch (AssertionError e) {
                throw new AssertionError("Ca: " + testCase.getKey(), e);
            }
            assertThat(countIdentities(token.getSubject())).as(testCase.getKey()).isZero();
            if (token.getEmail() != null) {
                assertThat(countUsersByEmail(token.getEmail())).as(testCase.getKey()).isZero();
            }
        }
        postGoogleSignIn("not-a-jwt").andExpect(status().isUnauthorized());
    }

    @Test
    void missingOrBlankIdToken_returns400() throws Exception {
        mockMvc.perform(post(GOOGLE_SIGN_IN).with(randomRemoteAddress())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'idToken')].message", contains("Thiếu idToken")));
        postGoogleSignIn("   ").andExpect(status().isBadRequest());
    }

    @Test
    void ignoresStaleBearerHeader() throws Exception {
        mockMvc.perform(googleSignInRequest(googleTokens.idToken().sign())
                        .header(HttpHeaders.AUTHORIZATION, bearer("expired-or-garbage")))
                .andExpect(status().isOk());
    }
}
