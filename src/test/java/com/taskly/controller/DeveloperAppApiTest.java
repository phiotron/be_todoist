package com.taskly.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M13cApiTestBase;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** /api/v1/developer/apps (plan M13c, F1). */
class DeveloperAppApiTest extends M13cApiTestBase {

    private static final String APPS = "/api/v1/developer/apps";

    @Test
    void create_returnsSecretOnce_andStoresOnlyItsHash() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());

        ResultActions created = authPost(owner.accessToken(), APPS,
                "{\"name\": \"  Công cụ của tôi \", \"redirectUri\": \"https://app.example/cb\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Công cụ của tôi"))
                .andExpect(jsonPath("$.clientId").value(org.hamcrest.Matchers.startsWith("tkc_")))
                .andExpect(jsonPath("$.clientSecret").value(org.hamcrest.Matchers.startsWith("tks_")))
                .andExpect(jsonPath("$.webhook").value(nullValue()));
        String secret = readString(created, "$.clientSecret");
        UUID id = readUuid(created, "$.id");

        String stored = jdbcTemplate.queryForObject("select client_secret_hash from api_clients where id = ?",
                String.class, id);
        Assertions.assertThat(stored).hasSize(64).doesNotContain(secret).isNotEqualTo(secret);

        authGet(owner.accessToken(), APPS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].clientSecret").value(nullValue()));
    }

    @Test
    void create_validatesNameAndRedirectUri() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        authPost(owner.accessToken(), APPS, "{\"name\": \"\", \"redirectUri\": \"https://a.example/cb\"}")
                .andExpect(status().isBadRequest());
        authPost(owner.accessToken(), APPS,
                "{\"name\": \"%s\", \"redirectUri\": \"https://a.example/cb\"}".formatted("x".repeat(101)))
                .andExpect(status().isBadRequest());
        authPost(owner.accessToken(), APPS, "{\"name\": \"ok\", \"redirectUri\": \"http://evil.example/cb\"}")
                .andExpect(status().isBadRequest());
        authPost(owner.accessToken(), APPS, "{\"name\": \"ok\", \"redirectUri\": \"javascript:alert(1)\"}")
                .andExpect(status().isBadRequest());
        authPost(owner.accessToken(), APPS, "{\"name\": \"ok\"}").andExpect(status().isBadRequest());
    }

    @Test
    void validationErrorsAreLocalized() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(APPS)
                        .header("Authorization", bearer(owner.accessToken())).header("Accept-Language", "en")
                        .contentType("application/json")
                        .content("{\"name\": \"\", \"redirectUri\": \"https://a.example/cb\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The application name must be 1-100 characters"));
    }

    @Test
    void update_changesNameAndRedirect_keepsOthers() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        App app = createApp(owner.accessToken());

        authPatch(owner.accessToken(), APPS + "/" + app.id(), "{\"name\": \"Tên mới\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tên mới"))
                .andExpect(jsonPath("$.redirectUri").value(REDIRECT));
        authPatch(owner.accessToken(), APPS + "/" + app.id(), "{\"redirectUri\": \"http://localhost:3000/cb\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tên mới"))
                .andExpect(jsonPath("$.redirectUri").value("http://localhost:3000/cb"));
        authPatch(owner.accessToken(), APPS + "/" + app.id(), "{\"redirectUri\": \"http://evil.example\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    void rotateSecret_invalidatesTheOldOne() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        App app = createApp(owner.accessToken());
        String newSecret = readString(authPost(owner.accessToken(), APPS + "/" + app.id() + "/secret")
                .andExpect(status().isOk()), "$.clientSecret");
        Assertions.assertThat(newSecret).startsWith("tks_").isNotEqualTo(app.secret());

        String verifier = newVerifier();
        String code = authorizeCode(owner.accessToken(), app, "data:read", challengeOf(verifier));
        tokenRequest("authorization_code", app.clientId(), app.secret(), code, app.redirectUri(), verifier)
                .andExpect(status().isUnauthorized());
        // mã chưa bị đốt vì xác thực app thất bại trước, dùng secret mới được
        tokenRequest("authorization_code", app.clientId(), newSecret, code, app.redirectUri(), verifier)
                .andExpect(status().isOk());
    }

    @Test
    void otherUsersAppIs404_andDeleteRemovesTokensAndWebhook() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        RegisteredUser stranger = registerUser(uniqueEmail());
        App app = createApp(owner.accessToken());
        String oauth = oauthToken(owner.accessToken(), app, "data:read");

        authPatch(stranger.accessToken(), APPS + "/" + app.id(), "{\"name\": \"x\"}").andExpect(status().isNotFound());
        authDelete(stranger.accessToken(), APPS + "/" + app.id()).andExpect(status().isNotFound());
        authPost(stranger.accessToken(), APPS + "/" + app.id() + "/secret").andExpect(status().isNotFound());
        authGet(stranger.accessToken(), APPS).andExpect(jsonPath("$.length()").value(0));
        authGet(oauth, "/api/v1/projects").andExpect(status().isOk());

        authDelete(owner.accessToken(), APPS + "/" + app.id()).andExpect(status().isNoContent());
        authGet(oauth, "/api/v1/projects").andExpect(status().isUnauthorized());
        authDelete(owner.accessToken(), APPS + "/" + app.id()).andExpect(status().isNotFound());
        Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from oauth_tokens where api_client_id = ?", Integer.class, app.id())).isZero();
    }

    @Test
    void deletingTheAccountRemovesDeveloperData() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        App app = createApp(owner.accessToken());
        oauthToken(owner.accessToken(), app, "data:read");
        authPost(owner.accessToken(), "/api/v1/me/push-tokens", "{\"token\": \"t-1\", \"platform\": \"ANDROID\"}")
                .andExpect(status().isNoContent());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/me")
                        .header("Authorization", bearer(owner.accessToken())).contentType("application/json")
                        .content("{\"password\": \"%s\"}".formatted(FIXTURE_PASSWORD)))
                .andExpect(status().isNoContent());

        for (String table : new String[] {"api_clients", "oauth_tokens", "oauth_authorization_codes", "push_tokens"}) {
            Assertions.assertThat(jdbcTemplate.queryForObject("select count(*) from " + table + " where "
                    + (table.equals("api_clients") ? "owner_id" : "user_id") + " = ?", Integer.class, owner.id()))
                    .as(table).isZero();
        }
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(APPS))
                .andExpect(status().isUnauthorized());
    }
}
