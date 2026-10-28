package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Cài đặt Quick Add trong me/settings (M6b, contract mục 2 + 8.2): quickAddChips, show action labels / description. */
class QuickAddSettingsApiTest extends IntegrationTestBase {

    private static final String SETTINGS = "/api/v1/me/settings";
    private static final String CHIPS_VI =
            "Chip Quick Add phải là các chip hợp lệ, không trùng và luôn có PROJECT";
    private static final String CHIPS_EN =
            "Quick Add chips must be known chips, without duplicates, and always include PROJECT";

    private Instant updatedAt(UUID userId) {
        return jdbcTemplate.queryForObject("select updated_at from users where id = ?", Timestamp.class, userId)
                .toInstant();
    }

    @Test
    void get_newAccount_hasQuickAddDefaults() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authGet(user.accessToken(), SETTINGS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quickAddChips", contains("PROJECT", "DATE", "ASSIGNEE")))
                .andExpect(jsonPath("$.quickAddShowActionLabels").value(true))
                .andExpect(jsonPath("$.quickAddShowDescription").value(true));
    }

    @Test
    void patch_savesOrderAndSwitches() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"quickAddChips": ["DATE", "PRIORITY", "PROJECT", "LABELS"],
                 "quickAddShowActionLabels": false, "quickAddShowDescription": false}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quickAddChips", contains("DATE", "PRIORITY", "PROJECT", "LABELS")))
                .andExpect(jsonPath("$.quickAddShowActionLabels").value(false))
                .andExpect(jsonPath("$.quickAddShowDescription").value(false));

        authGet(user.accessToken(), SETTINGS)
                .andExpect(jsonPath("$.quickAddChips", contains("DATE", "PRIORITY", "PROJECT", "LABELS")));
        Map<String, Object> row = jdbcTemplate.queryForMap("select quick_add_chips, quick_add_show_action_labels, "
                + "quick_add_show_description from users where id = ?", user.id());
        assertThat(row).containsEntry("quick_add_chips", "DATE,PRIORITY,PROJECT,LABELS")
                .containsEntry("quick_add_show_action_labels", false)
                .containsEntry("quick_add_show_description", false);
    }

    @Test
    void patch_withoutQuickAddFields_keepsThem() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPatch(user.accessToken(), SETTINGS, """
                {"quickAddChips": ["PROJECT"], "quickAddShowDescription": false}
                """).andExpect(status().isOk());

        authPatch(user.accessToken(), SETTINGS, """
                {"startDay": 3, "quickAddChips": null, "quickAddShowDescription": null}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quickAddChips", contains("PROJECT")))
                .andExpect(jsonPath("$.quickAddShowDescription").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[\"DATE\"]", "[\"PROJECT\", \"DATE\", \"DATE\"]", "[\"PROJECT\", \"COMMENTS\"]",
            "[\"project\"]", "[\"PROJECT\", null]"})
    void patch_rejectsInvalidChipLists(String chips) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"quickAddChips": %s, "startDay": 5}
                """.formatted(chips))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("quickAddChips"))
                .andExpect(jsonPath("$.errors[0].message").value(CHIPS_VI));

        authGet(user.accessToken(), SETTINGS)
                .andExpect(jsonPath("$.quickAddChips", contains("PROJECT", "DATE", "ASSIGNEE")))
                .andExpect(jsonPath("$.startDay").value(1));
    }

    @Test
    void validationMessageIsTranslatedToEnglish() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patch(SETTINGS)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quickAddChips": ["DATE"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(CHIPS_EN));
    }

    @Test
    void patch_sameValues_keepsUpdatedAt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        Instant before = updatedAt(user.id());
        clock.advance(Duration.ofMinutes(1));

        authPatch(user.accessToken(), SETTINGS, """
                {"quickAddChips": ["PROJECT", "DATE", "ASSIGNEE"], "quickAddShowActionLabels": true,
                 "quickAddShowDescription": true}
                """).andExpect(status().isOk());

        assertThat(updatedAt(user.id())).isEqualTo(before);
    }

    @Test
    void reset_restoresQuickAddDefaults() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPatch(user.accessToken(), SETTINGS, """
                {"quickAddChips": ["LABELS", "PROJECT"], "quickAddShowActionLabels": false,
                 "quickAddShowDescription": false}
                """).andExpect(status().isOk());

        authPost(user.accessToken(), SETTINGS + "/reset")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quickAddChips", contains("PROJECT", "DATE", "ASSIGNEE")))
                .andExpect(jsonPath("$.quickAddShowActionLabels").value(true))
                .andExpect(jsonPath("$.quickAddShowDescription").value(true));
    }
}
