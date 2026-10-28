package com.taskly.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M13cApiTestBase;
import java.time.Duration;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** OAuth authorization code + PKCE (plan M13c, F2). */
class OAuthFlowApiTest extends M13cApiTestBase {

    @Test
    void preview_showsAppNameAndScopes() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        authGet(user.accessToken(), "/api/v1/oauth/authorize/preview?clientId=" + app.clientId()
                + "&redirectUri=" + REDIRECT + "&scope=data:read,task:add")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appName").value("App thử"))
                .andExpect(jsonPath("$.clientId").value(app.clientId()))
                .andExpect(jsonPath("$.scopes[0]").value("data:read"))
                .andExpect(jsonPath("$.scopes[1]").value("task:add"));
    }

    @Test
    void preview_rejectsUnknownClientRedirectMismatchAndBadScope() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        authGet(user.accessToken(), "/api/v1/oauth/authorize/preview?clientId=nope&redirectUri=" + REDIRECT
                + "&scope=data:read").andExpect(status().isBadRequest());
        authGet(user.accessToken(), "/api/v1/oauth/authorize/preview?clientId=" + app.clientId()
                + "&redirectUri=https://evil.example/cb&scope=data:read").andExpect(status().isBadRequest());
        authGet(user.accessToken(), "/api/v1/oauth/authorize/preview?clientId=" + app.clientId()
                + "&redirectUri=" + REDIRECT + "&scope=admin").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("oauth-invalid-scope"));
        authGet(user.accessToken(), "/api/v1/oauth/authorize/preview").andExpect(status().isBadRequest());
    }

    @Test
    void fullFlow_issuesTokenThatWorksWithGrantedScope() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        String verifier = newVerifier();

        ResultActions authorized = authorize(user.accessToken(), app, "data:read", challengeOf(verifier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.redirectUrl").value(startsWith(REDIRECT + "?code=")))
                .andExpect(jsonPath("$.redirectUrl").value(containsString("&state=xyz")));
        String code = org.springframework.web.util.UriComponentsBuilder.fromUriString(
                readString(authorized, "$.redirectUrl")).build().getQueryParams().getFirst("code");

        ResultActions token = tokenRequest("authorization_code", app.clientId(), app.secret(), code, REDIRECT, verifier)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.scope").value("data:read"))
                .andExpect(jsonPath("$.access_token").value(startsWith("tkl_")));
        String accessToken = readString(token, "$.access_token");

        // token và mã đều chỉ lưu băm
        Assertions.assertThat(jdbcTemplate.queryForList("select token_hash from oauth_tokens where user_id = ?",
                String.class, user.id())).singleElement().satisfies(hash -> Assertions.assertThat(hash)
                .hasSize(64).isNotEqualTo(accessToken));
        Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from oauth_authorization_codes where code_hash = ?", Integer.class, code)).isZero();

        authGet(accessToken, "/api/v1/projects").andExpect(status().isOk());
        authGet(accessToken, "/api/v1/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.id().toString()));
    }

    @Test
    void authorize_requiresPkceS256_andValidClient() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        authPost(user.accessToken(), "/api/v1/oauth/authorize", """
                {"clientId": "%s", "redirectUri": "%s", "scope": "data:read"}
                """.formatted(app.clientId(), REDIRECT)).andExpect(status().isBadRequest());
        authPost(user.accessToken(), "/api/v1/oauth/authorize", """
                {"clientId": "%s", "redirectUri": "%s", "scope": "data:read", "codeChallenge": "%s",
                 "codeChallengeMethod": "plain"}
                """.formatted(app.clientId(), REDIRECT, challengeOf(newVerifier()))).andExpect(status().isBadRequest());
        authPost(user.accessToken(), "/api/v1/oauth/authorize", """
                {"clientId": "%s", "redirectUri": "%s", "scope": "data:read", "codeChallenge": "short",
                 "codeChallengeMethod": "S256"}
                """.formatted(app.clientId(), REDIRECT)).andExpect(status().isBadRequest());
        authPost(user.accessToken(), "/api/v1/oauth/authorize", """
                {"clientId": "tkc_unknown", "redirectUri": "%s", "scope": "data:read", "codeChallenge": "%s",
                 "codeChallengeMethod": "S256"}
                """.formatted(REDIRECT, challengeOf(newVerifier()))).andExpect(status().isBadRequest());
        authPost(user.accessToken(), "/api/v1/oauth/authorize", "{}").andExpect(status().isBadRequest());
    }

    @Test
    void token_rejectsWrongSecretWithInvalidClient() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        String verifier = newVerifier();
        String code = authorizeCode(user.accessToken(), app, "data:read", challengeOf(verifier));

        tokenRequest("authorization_code", app.clientId(), "tks_wrong", code, REDIRECT, verifier)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_client"))
                .andExpect(jsonPath("$.code").value("oauth-invalid-client"));
        tokenRequest("authorization_code", "tkc_unknown", app.secret(), code, REDIRECT, verifier)
                .andExpect(status().isUnauthorized());
        tokenRequest("authorization_code", app.clientId(), null, code, REDIRECT, verifier)
                .andExpect(status().isUnauthorized());
    }

    @Test
    void token_rejectsBadGrantTypeAndMissingParams() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        tokenRequest("password", app.clientId(), app.secret(), "c", REDIRECT, "v")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("unsupported_grant_type"));
        tokenRequest(null, app.clientId(), app.secret(), "c", REDIRECT, "v").andExpect(status().isBadRequest());
        tokenRequest("authorization_code", app.clientId(), app.secret(), null, REDIRECT, "v")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        tokenRequest("authorization_code", app.clientId(), app.secret(), "c", REDIRECT, null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void token_rejectsWrongVerifierWrongRedirectUnknownCodeAndOtherClientsCode() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        App other = createApp(user.accessToken());
        String verifier = newVerifier();

        String code1 = authorizeCode(user.accessToken(), app, "data:read", challengeOf(verifier));
        tokenRequest("authorization_code", app.clientId(), app.secret(), code1, REDIRECT, newVerifier())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));

        String code2 = authorizeCode(user.accessToken(), app, "data:read", challengeOf(verifier));
        tokenRequest("authorization_code", app.clientId(), app.secret(), code2, "https://other.example/cb", verifier)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));

        tokenRequest("authorization_code", app.clientId(), app.secret(), "no-such-code", REDIRECT, verifier)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));

        String code3 = authorizeCode(user.accessToken(), app, "data:read", challengeOf(verifier));
        tokenRequest("authorization_code", other.clientId(), other.secret(), code3, REDIRECT, verifier)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void token_rejectsExpiredCode() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        String verifier = newVerifier();
        String code = authorizeCode(user.accessToken(), app, "data:read", challengeOf(verifier));
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        tokenRequest("authorization_code", app.clientId(), app.secret(), code, REDIRECT, verifier)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void reusedCode_isRejected_andRevokesTheTokenIssuedFromIt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        String verifier = newVerifier();
        String code = authorizeCode(user.accessToken(), app, "data:read", challengeOf(verifier));
        String accessToken = readString(tokenRequest("authorization_code", app.clientId(), app.secret(), code, REDIRECT,
                verifier).andExpect(status().isOk()), "$.access_token");
        authGet(accessToken, "/api/v1/projects").andExpect(status().isOk());

        tokenRequest("authorization_code", app.clientId(), app.secret(), code, REDIRECT, verifier)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_grant"));
        authGet(accessToken, "/api/v1/projects").andExpect(status().isUnauthorized());
    }

    @Test
    void revoke_byTheOwningApp_killsTheToken_andUnknownTokenIsStill200() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        App other = createApp(user.accessToken());
        String accessToken = oauthToken(user.accessToken(), app, "data:read");

        // app khác không thu hồi được token của app này, nhưng vẫn 200
        mockMvc.perform(post("/api/v1/oauth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_id", other.clientId()).param("client_secret", other.secret())
                .param("token", accessToken)).andExpect(status().isOk());
        authGet(accessToken, "/api/v1/projects").andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/oauth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_id", app.clientId()).param("client_secret", app.secret())
                .param("token", accessToken)).andExpect(status().isOk());
        authGet(accessToken, "/api/v1/projects").andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/oauth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_id", app.clientId()).param("client_secret", app.secret())
                .param("token", "tkl_unknown")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/oauth/revoke").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("client_id", app.clientId()).param("client_secret", "wrong")
                .param("token", accessToken)).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidOrUnknownOAuthTokenIs401_andWrongTypeBearerStillRejected() throws Exception {
        authGet("tkl_" + "a".repeat(43), "/api/v1/projects").andExpect(status().isUnauthorized());
        // token mờ gửi tới endpoint công khai không gây lỗi (resolver bỏ qua header)
        mockMvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());
    }

    @Test
    void oauthTokenIsNotAcceptedAsAnAppSession() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        String oauth = oauthToken(user.accessToken(), app, "data:delete,data:read_write,task:add,project:delete");

        // các endpoint của app Taskly không mở cho token OAuth, kể cả scope rộng nhất
        authGet(oauth, "/api/v1/me/settings").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient-scope"));
        authGet(oauth, "/api/v1/developer/apps").andExpect(status().isForbidden());
        authGet(oauth, "/api/v1/me/authorized-apps").andExpect(status().isForbidden());
        authPost(oauth, "/api/v1/me/push-tokens", "{\"token\": \"t\", \"platform\": \"ANDROID\"}")
                .andExpect(status().isForbidden());
        authPost(oauth, "/api/v1/oauth/authorize", "{}").andExpect(status().isForbidden());
        authPatch(oauth, "/api/v1/me", "{\"displayName\": \"x\"}").andExpect(status().isForbidden());
        authPost(oauth, "/api/v1/me/logout-all").andExpect(status().isForbidden());
    }

    @Test
    void scopesDecideWhatTheTokenCanDo() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        String inbox = testData.inboxOf(user.id()).toString();
        String read = oauthToken(user.accessToken(), app, "data:read");
        String add = oauthToken(user.accessToken(), app, "task:add");
        String write = oauthToken(user.accessToken(), app, "data:read_write");
        String del = oauthToken(user.accessToken(), app, "data:delete");
        String projectDel = oauthToken(user.accessToken(), app, "project:delete");
        String body = "{\"content\": \"Việc từ app ngoài\", \"projectId\": \"%s\"}".formatted(inbox);

        authPost(read, "/api/v1/tasks", body).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("insufficient-scope"));
        authGet(add, "/api/v1/tasks?view=inbox").andExpect(status().isForbidden());
        String taskId = readString(authPost(add, "/api/v1/tasks", body).andExpect(status().isCreated()), "$.id");
        authGet(read, "/api/v1/tasks/" + taskId).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Việc từ app ngoài"));
        authDelete(write, "/api/v1/tasks/" + taskId).andExpect(status().isForbidden());
        authDelete(del, "/api/v1/tasks/" + taskId).andExpect(status().isNoContent());

        authPost(write, "/api/v1/labels", "{\"name\": \"tu-app-ngoai\"}").andExpect(status().isCreated());
        authPost(read, "/api/v1/labels", "{\"name\": \"khong-duoc\"}").andExpect(status().isForbidden());

        String projectId = readString(authPost(user.accessToken(), "/api/v1/projects", "{\"name\": \"Dự án thử\"}")
                .andExpect(status().isCreated()), "$.id");
        authDelete(del, "/api/v1/projects/" + projectId).andExpect(status().isForbidden());
        authDelete(projectDel, "/api/v1/projects/" + projectId).andExpect(status().isNoContent());
    }

    @Test
    void appJwtKeepsWorkingUnchanged() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authGet(user.accessToken(), "/api/v1/me/settings").andExpect(status().isOk());
        authGet(user.accessToken(), "/api/v1/projects").andExpect(status().isOk());
    }

    @Test
    void tokenEndpointIsPublicAndNeverCached() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        String verifier = newVerifier();
        String code = authorizeCode(user.accessToken(), app, "data:read", challengeOf(verifier));
        tokenRequest("authorization_code", app.clientId(), app.secret(), code, REDIRECT, verifier)
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Cache-Control", "no-store"));
    }
}
