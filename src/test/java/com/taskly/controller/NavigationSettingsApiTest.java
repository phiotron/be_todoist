package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

/**
 * M3x — cài đặt điều hướng trong me/settings (spec 3.1, 12.1.1; contract mục 2): vị trí nút +, Navigation menu
 * (Show task count + mục menu), Sync home view.
 */
class NavigationSettingsApiTest extends IntegrationTestBase {

    private static final String SETTINGS = "/api/v1/me/settings";
    private static final String PLACEMENT_VI = "Vị trí nút + chỉ nhận LEFT, CENTER hoặc RIGHT";
    private static final String PLACEMENT_EN = "Add button placement must be LEFT, CENTER or RIGHT";
    private static final String MENU_VI = "Mục menu điều hướng chỉ nhận INBOX, TODAY, UPCOMING, FILTERS_LABELS, "
            + "REPORTING, BROWSE_TEMPLATES, mỗi mục một lần";
    private static final String MENU_EN = "Navigation menu items must be INBOX, TODAY, UPCOMING, FILTERS_LABELS, "
            + "REPORTING or BROWSE_TEMPLATES, each at most once";

    private Instant updatedAt(UUID userId) {
        return jdbcTemplate.queryForObject("select updated_at from users where id = ?", Timestamp.class, userId)
                .toInstant();
    }

    @Test
    void get_newAccount_hasNavigationDefaults() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authGet(user.accessToken(), SETTINGS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addButtonPlacement").value("RIGHT"))
                .andExpect(jsonPath("$.syncHomeView").value(true))
                .andExpect(jsonPath("$.navMenuShowTaskCount").value(true))
                .andExpect(jsonPath("$.navMenuItems", contains(
                        "INBOX", "TODAY", "UPCOMING", "FILTERS_LABELS", "REPORTING", "BROWSE_TEMPLATES")));
    }

    @Test
    void patch_allNavigationFields_updatesAndPersists() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"addButtonPlacement": "CENTER", "syncHomeView": false, "navMenuShowTaskCount": false,
                 "navMenuItems": ["UPCOMING", "INBOX", "REPORTING"]}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addButtonPlacement").value("CENTER"))
                .andExpect(jsonPath("$.syncHomeView").value(false))
                .andExpect(jsonPath("$.navMenuShowTaskCount").value(false))
                .andExpect(jsonPath("$.navMenuItems", contains("UPCOMING", "INBOX", "REPORTING")));

        authGet(user.accessToken(), SETTINGS)
                .andExpect(jsonPath("$.addButtonPlacement").value("CENTER"))
                .andExpect(jsonPath("$.navMenuItems", contains("UPCOMING", "INBOX", "REPORTING")));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select add_button_placement, sync_home_view, nav_menu_show_task_count, nav_menu_items "
                        + "from users where id = ?", user.id());
        assertThat(row).containsEntry("add_button_placement", "CENTER")
                .containsEntry("sync_home_view", false)
                .containsEntry("nav_menu_show_task_count", false)
                .containsEntry("nav_menu_items", "UPCOMING,INBOX,REPORTING");
    }

    @Test
    void patch_emptyMenu_hidesEveryItem() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"navMenuItems": []}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.navMenuItems", empty()));

        authGet(user.accessToken(), SETTINGS).andExpect(jsonPath("$.navMenuItems", empty()));
        assertThat(jdbcTemplate.queryForObject("select nav_menu_items from users where id = ?", String.class,
                user.id())).isEmpty();
    }

    @Test
    void patch_withoutNavigationFields_keepsThem() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPatch(user.accessToken(), SETTINGS, """
                {"addButtonPlacement": "LEFT", "syncHomeView": false, "navMenuItems": ["TODAY"]}
                """).andExpect(status().isOk());

        authPatch(user.accessToken(), SETTINGS, """
                {"startDay": 3, "addButtonPlacement": null, "syncHomeView": null, "navMenuShowTaskCount": null,
                 "navMenuItems": null}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addButtonPlacement").value("LEFT"))
                .andExpect(jsonPath("$.syncHomeView").value(false))
                .andExpect(jsonPath("$.navMenuShowTaskCount").value(true))
                .andExpect(jsonPath("$.navMenuItems", contains("TODAY")));
    }

    @Test
    void patch_sameValues_keepsUpdatedAt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        Instant before = updatedAt(user.id());
        clock.advance(Duration.ofMinutes(1));

        authPatch(user.accessToken(), SETTINGS, """
                {"addButtonPlacement": "RIGHT", "syncHomeView": true, "navMenuShowTaskCount": true,
                 "navMenuItems": ["INBOX", "TODAY", "UPCOMING", "FILTERS_LABELS", "REPORTING", "BROWSE_TEMPLATES"]}
                """).andExpect(status().isOk());
        assertThat(updatedAt(user.id())).isEqualTo(before);

        authPatch(user.accessToken(), SETTINGS, """
                {"addButtonPlacement": "LEFT"}
                """).andExpect(status().isOk());
        assertThat(updatedAt(user.id())).isAfter(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"left", "MIDDLE", "", "Right"})
    void patch_unknownPlacement_returns400AndChangesNothing(String placement) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"addButtonPlacement": "%s", "syncHomeView": false}
                """.formatted(placement))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field", contains("addButtonPlacement")))
                .andExpect(jsonPath("$.errors[0].message").value(PLACEMENT_VI));

        authGet(user.accessToken(), SETTINGS)
                .andExpect(jsonPath("$.addButtonPlacement").value("RIGHT"))
                .andExpect(jsonPath("$.syncHomeView").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"INBOX\", \"INBOX\"]",
            "[\"BROWSE\"]",
            "[\"inbox\"]",
            "[\"INBOX\", null]",
            "[\"SEARCH\", \"TODAY\"]",
            "[\"INBOX\", \"TODAY\", \"UPCOMING\", \"FILTERS_LABELS\", \"REPORTING\", \"BROWSE_TEMPLATES\", \"INBOX\"]"})
    void patch_invalidMenuItems_returns400(String items) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, "{\"navMenuItems\": " + items + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("navMenuItems")))
                .andExpect(jsonPath("$.errors[0].message").value(MENU_VI));
    }

    @Test
    void validationMessagesAreTranslatedToEnglish() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patch(SETTINGS)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"addButtonPlacement": "TOP", "navMenuItems": ["HOME"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'addButtonPlacement')].message", contains(PLACEMENT_EN)))
                .andExpect(jsonPath("$.errors[?(@.field == 'navMenuItems')].message", contains(MENU_EN)));
    }

    @Test
    void reset_restoresNavigationDefaults() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPatch(user.accessToken(), SETTINGS, """
                {"addButtonPlacement": "LEFT", "syncHomeView": false, "navMenuShowTaskCount": false,
                 "navMenuItems": [], "timeZone": "Europe/London"}
                """).andExpect(status().isOk());

        authPost(user.accessToken(), SETTINGS + "/reset")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addButtonPlacement").value("RIGHT"))
                .andExpect(jsonPath("$.syncHomeView").value(true))
                .andExpect(jsonPath("$.navMenuShowTaskCount").value(true))
                .andExpect(jsonPath("$.navMenuItems", contains(
                        "INBOX", "TODAY", "UPCOMING", "FILTERS_LABELS", "REPORTING", "BROWSE_TEMPLATES")))
                .andExpect(jsonPath("$.timeZone").value("Europe/London"));
        authGet(user.accessToken(), SETTINGS).andExpect(jsonPath("$.addButtonPlacement").value("RIGHT"));
    }
}
