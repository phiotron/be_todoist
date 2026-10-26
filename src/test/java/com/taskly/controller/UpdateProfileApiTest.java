package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class UpdateProfileApiTest extends IntegrationTestBase {

    private static MockHttpServletRequestBuilder patchMe(String accessToken, String json) {
        return patch("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private Instant updatedAt(UUID userId) {
        return jdbcTemplate.queryForObject("select updated_at from users where id = ?", Timestamp.class, userId)
                .toInstant();
    }

    @Test
    void patch_updatesDisplayNameAndReturnsProfile() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchMe(user.accessToken(), """
                        {"displayName": "  Trần Thị B  "}
                        """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(user.id().toString()))
                .andExpect(jsonPath("$.email").value(user.email()))
                .andExpect(jsonPath("$.displayName").value("Trần Thị B"))
                .andExpect(content().string(not(containsString("password"))));

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Trần Thị B"));
    }

    @Test
    void patch_sameDisplayNameKeepsUpdatedAt_newNameChangesIt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail()); // displayName = "Người dùng thử"
        Instant before = updatedAt(user.id());
        clock.advance(Duration.ofMinutes(1));

        mockMvc.perform(patchMe(user.accessToken(), """
                        {"displayName": " Người dùng thử "}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Người dùng thử"));
        assertThat(updatedAt(user.id())).isEqualTo(before);

        mockMvc.perform(patchMe(user.accessToken(), """
                        {"displayName": "Tên khác"}
                        """))
                .andExpect(status().isOk());
        assertThat(updatedAt(user.id())).isAfter(before);
    }

    @Test
    void patch_blankOrTooLongName_returns400_and100CharactersIsAccepted() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchMe(user.accessToken(), """
                        {"displayName": "   "}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[?(@.field == 'displayName')].message",
                        contains("Vui lòng nhập tên hiển thị")));
        mockMvc.perform(patchMe(user.accessToken(), "{\"displayName\": \"%s\"}".formatted("a".repeat(101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'displayName')].message",
                        contains("Tên hiển thị tối đa 100 ký tự")));
        mockMvc.perform(patchMe(user.accessToken(), "{\"displayName\": \"%s\"}".formatted("a".repeat(100))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("a".repeat(100)));
    }

    @Test
    void patch_missingField_returns400() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchMe(user.accessToken(), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'displayName')].message",
                        contains("Vui lòng nhập tên hiển thị")));
    }

    @Test
    void patch_withoutToken_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/me").contentType(MediaType.APPLICATION_JSON).content("""
                        {"displayName": "Ai đó"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void patch_validationMessageInEnglish() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchMe(user.accessToken(), """
                        {"displayName": "   "}
                        """).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The submitted data is invalid"))
                .andExpect(jsonPath("$.errors[?(@.field == 'displayName')].message",
                        contains("Please enter a display name")));
    }
}
