package com.taskly.controller;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M13cApiTestBase;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** me/authorized-apps (plan M13c, F4). */
class AuthorizedAppApiTest extends M13cApiTestBase {

    private static final String APPS = "/api/v1/me/authorized-apps";

    @Test
    void empty_whenNothingGranted() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authGet(user.accessToken(), APPS).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void list_mergesTokensPerApp_withUnionOfScopes_andLastUse() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App one = createApp(user.accessToken());
        App two = createApp(user.accessToken());
        String firstToken = oauthToken(user.accessToken(), one, "data:read");
        clock.advance(Duration.ofMinutes(5));
        oauthToken(user.accessToken(), one, "task:add");
        clock.advance(Duration.ofMinutes(5));
        oauthToken(user.accessToken(), two, "data:read_write");

        authGet(user.accessToken(), APPS).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].clientId").value(two.clientId()))
                .andExpect(jsonPath("$[0].name").value("App thử"))
                .andExpect(jsonPath("$[0].scopes[0]").value("data:read_write"))
                .andExpect(jsonPath("$[0].lastUsedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].clientId").value(one.clientId()))
                .andExpect(jsonPath("$[1].scopes.length()").value(2));

        authGet(firstToken, "/api/v1/projects").andExpect(status().isOk());
        authGet(user.accessToken(), APPS).andExpect(jsonPath("$[1].lastUsedAt").isNotEmpty());
    }

    @Test
    void revoke_killsAllTokensOfThatAppOnly_andIsolatesUsers() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        RegisteredUser other = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        App unrelated = createApp(user.accessToken());
        String a = oauthToken(user.accessToken(), app, "data:read");
        String b = oauthToken(user.accessToken(), app, "data:read_write");
        String otherUsers = oauthToken(other.accessToken(), app, "data:read");
        String keep = oauthToken(user.accessToken(), unrelated, "data:read");

        authDelete(user.accessToken(), APPS + "/" + app.clientId()).andExpect(status().isNoContent());
        authGet(a, "/api/v1/projects").andExpect(status().isUnauthorized());
        authGet(b, "/api/v1/projects").andExpect(status().isUnauthorized());
        authGet(keep, "/api/v1/projects").andExpect(status().isOk());
        authGet(otherUsers, "/api/v1/projects").andExpect(status().isOk());
        authGet(user.accessToken(), APPS).andExpect(jsonPath("$.length()").value(1));

        authDelete(user.accessToken(), APPS + "/" + app.clientId()).andExpect(status().isNotFound());
        authDelete(user.accessToken(), APPS + "/tkc_unknown").andExpect(status().isNotFound());
    }

    @Test
    void theAppCanBeAuthorizedAgainAfterRevoke() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        oauthToken(user.accessToken(), app, "data:read");
        authDelete(user.accessToken(), APPS + "/" + app.clientId()).andExpect(status().isNoContent());
        String again = oauthToken(user.accessToken(), app, "data:read");
        authGet(again, "/api/v1/projects").andExpect(status().isOk());
    }
}
