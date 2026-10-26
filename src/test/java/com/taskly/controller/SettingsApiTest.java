package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class SettingsApiTest extends IntegrationTestBase {

    private static final String SETTINGS = "/api/v1/me/settings";

    private static MockHttpServletRequestBuilder getSettings(String accessToken) {
        return get(SETTINGS).header(HttpHeaders.AUTHORIZATION, bearer(accessToken));
    }

    private static MockHttpServletRequestBuilder patchSettings(String accessToken, String json) {
        return patch(SETTINGS).header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static MockHttpServletRequestBuilder resetSettings(String accessToken) {
        return post(SETTINGS + "/reset").header(HttpHeaders.AUTHORIZATION, bearer(accessToken));
    }

    private Instant updatedAt(UUID userId) {
        return jdbcTemplate.queryForObject("select updated_at from users where id = ?", Timestamp.class, userId)
                .toInstant();
    }

    @Test
    void get_newUser_returnsDefaults() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(getSettings(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.theme").value("SYSTEM"))
                .andExpect(jsonPath("$.locale").value("vi"))
                .andExpect(jsonPath("$.timeZone").value("Asia/Ho_Chi_Minh"));
    }

    @Test
    void patch_allFields_updatesSettingsAndProfile() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"theme": "DARK", "locale": "en", "timeZone": "Europe/London"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("DARK"))
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.timeZone").value("Europe/London"));

        mockMvc.perform(getSettings(user.accessToken()))
                .andExpect(jsonPath("$.theme").value("DARK"))
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.timeZone").value("Europe/London"));
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.timeZone").value("Europe/London"));
        assertThat(jdbcTemplate.queryForObject("select theme from users where id = ?", String.class, user.id()))
                .isEqualTo("DARK");
    }

    @Test
    void patch_onlyTheme_keepsOtherFields() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"theme": "LIGHT"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("LIGHT"))
                .andExpect(jsonPath("$.locale").value("vi"))
                .andExpect(jsonPath("$.timeZone").value("Asia/Ho_Chi_Minh"));
    }

    @Test
    void patch_withoutRealChange_keepsUpdatedAt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        Instant before = updatedAt(user.id());
        clock.advance(Duration.ofMinutes(1));

        mockMvc.perform(patchSettings(user.accessToken(), "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("SYSTEM"));
        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"theme": "SYSTEM", "locale": "vi", "timeZone": "Asia/Ho_Chi_Minh"}
                        """))
                .andExpect(status().isOk());
        assertThat(updatedAt(user.id())).isEqualTo(before);

        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"locale": "en"}
                        """))
                .andExpect(status().isOk());
        assertThat(updatedAt(user.id())).isAfter(before);
    }

    @Test
    void patch_invalidValues_returns400PerFieldAndChangesNothing() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"theme": "PURPLE", "locale": "fr", "timeZone": "Mars/Base"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("theme", "locale", "timeZone")))
                .andExpect(jsonPath("$.errors[?(@.field == 'theme')].message",
                        contains("Giao diện chỉ nhận LIGHT, DARK hoặc SYSTEM")))
                .andExpect(jsonPath("$.errors[?(@.field == 'locale')].message",
                        contains("Ngôn ngữ chỉ nhận vi hoặc en")))
                .andExpect(jsonPath("$.errors[?(@.field == 'timeZone')].message",
                        contains("Múi giờ không hợp lệ")));

        mockMvc.perform(getSettings(user.accessToken()))
                .andExpect(jsonPath("$.theme").value("SYSTEM"))
                .andExpect(jsonPath("$.locale").value("vi"))
                .andExpect(jsonPath("$.timeZone").value("Asia/Ho_Chi_Minh"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"+07:00", "GMT+7", "asia/ho_chi_minh", "Mars/Base", ""})
    void patch_invalidTimeZone_returns400(String timeZone) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"timeZone": "%s"}
                        """.formatted(timeZone)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'timeZone')].message", contains("Múi giờ không hợp lệ")));
    }

    @Test
    void patch_wrongCaseThemeOrLocale_returns400() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"theme": "dark", "locale": "EN"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("theme", "locale")));
    }

    @Test
    void reset_restoresDefaultsButKeepsTimeZone() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"theme": "DARK", "locale": "en", "timeZone": "Europe/London"}
                        """))
                .andExpect(status().isOk());

        mockMvc.perform(resetSettings(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("SYSTEM"))
                .andExpect(jsonPath("$.locale").value("vi"))
                .andExpect(jsonPath("$.timeZone").value("Europe/London"));

        mockMvc.perform(getSettings(user.accessToken()))
                .andExpect(jsonPath("$.theme").value("SYSTEM"))
                .andExpect(jsonPath("$.locale").value("vi"))
                .andExpect(jsonPath("$.timeZone").value("Europe/London"));
    }

    @Test
    void endpoints_withoutToken_return401() throws Exception {
        mockMvc.perform(get(SETTINGS)).andExpect(status().isUnauthorized());
        mockMvc.perform(patch(SETTINGS).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(SETTINGS + "/reset")).andExpect(status().isUnauthorized());
    }

    @Test
    void patch_invalidValue_messageInEnglish() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"theme": "PURPLE"}
                        """).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'theme')].message",
                        contains("Theme must be LIGHT, DARK or SYSTEM")));
    }
}
