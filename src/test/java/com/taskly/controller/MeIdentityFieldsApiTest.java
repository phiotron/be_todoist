package com.taskly.controller;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.IdentityProvider;
import com.taskly.entity.User;
import com.taskly.repository.UserRepository;
import com.taskly.security.JwtTokenIssuer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** spec 12.1: GET me từ M5 kèm hasPassword, linkedProviders (AuthResponse.user cũng vậy). */
class MeIdentityFieldsApiTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenIssuer jwtTokenIssuer;

    @Test
    void passwordAccount_hasPasswordTrueAndNoLinkedProviders() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.linkedProviders").isArray())
                .andExpect(jsonPath("$.linkedProviders").isEmpty());
    }

    @Test
    void googleOnlyAccount_hasPasswordFalseAndGoogleProvider() throws Exception {
        String email = uniqueEmail();
        User user = new User(email, null, "Chỉ có Google");
        user.linkIdentity(IdentityProvider.GOOGLE, "google-sub-" + UUID.randomUUID(), email);
        userRepository.saveAndFlush(user);

        mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(jwtTokenIssuer.issueAccessToken(user.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(false))
                .andExpect(jsonPath("$.linkedProviders", contains("GOOGLE")));
    }

    @Test
    void authResponseUserCarriesSameFields() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email": "%s", "password": "%s", "displayName": "A"}
                        """.formatted(uniqueEmail(), FIXTURE_PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.hasPassword").value(true))
                .andExpect(jsonPath("$.user.linkedProviders").isEmpty());
    }
}
