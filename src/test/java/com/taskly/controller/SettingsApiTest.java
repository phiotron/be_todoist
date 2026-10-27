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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** me/settings theo spec bản 3 (mục 10, 12.1.1): theme ×7 + appearance, ngày & giờ, trang mở đầu, vuốt, thanh tab. */
class SettingsApiTest extends IntegrationTestBase {

    private static final String SETTINGS = "/api/v1/me/settings";

    /** Gửi đủ giá trị mặc định của spec mục 10 (trừ múi giờ) — dùng để kiểm "không đổi gì". */
    private static final String DEFAULTS_JSON = """
            {"theme": "TASKLY", "appearance": "AUTO", "locale": "vi", "timeZone": "Asia/Ho_Chi_Minh",
             "startDay": 1, "nextWeek": 1, "weekendStartDay": 6, "dateFormat": "DMY", "timeFormat": "H24",
             "startPage": "TODAY", "smartDateRecognition": true, "swipeLeft": "SCHEDULE", "swipeRight": "COMPLETE",
             "navTabs": ["INBOX", "TODAY", "UPCOMING", "BROWSE"]}
            """;

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

    private static void expectDefaults(ResultActions actions, String timeZone) throws Exception {
        actions.andExpect(jsonPath("$.theme").value("TASKLY"))
                .andExpect(jsonPath("$.appearance").value("AUTO"))
                .andExpect(jsonPath("$.locale").value("vi"))
                .andExpect(jsonPath("$.timeZone").value(timeZone))
                .andExpect(jsonPath("$.startDay").value(1))
                .andExpect(jsonPath("$.nextWeek").value(1))
                .andExpect(jsonPath("$.weekendStartDay").value(6))
                .andExpect(jsonPath("$.dateFormat").value("DMY"))
                .andExpect(jsonPath("$.timeFormat").value("H24"))
                .andExpect(jsonPath("$.startPage").value("TODAY"))
                .andExpect(jsonPath("$.smartDateRecognition").value(true))
                .andExpect(jsonPath("$.swipeLeft").value("SCHEDULE"))
                .andExpect(jsonPath("$.swipeRight").value("COMPLETE"))
                .andExpect(jsonPath("$.navTabs", contains("INBOX", "TODAY", "UPCOMING", "BROWSE")));
    }

    private Instant updatedAt(UUID userId) {
        return jdbcTemplate.queryForObject("select updated_at from users where id = ?", Timestamp.class, userId)
                .toInstant();
    }

    /** Dự án thường (không phải Inbox) trong workspace cá nhân của user — chèn bằng SQL vì M3 chưa có API dự án. */
    private UUID insertProject(UUID ownerId) {
        UUID workspaceId = UUID.fromString(jdbcTemplate.queryForObject(
                "select id::text from workspaces where owner_id = ? and type = 'PERSONAL'", String.class, ownerId));
        UUID projectId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into projects (id, workspace_id, name, color, is_inbox, view_style, sort_order, "
                        + "created_at, updated_at) values (?, ?, 'Ghim', 'charcoal', false, 'LIST', 1024, now(), now())",
                projectId, workspaceId);
        return projectId;
    }

    @Test
    void get_newUser_returnsSpecDefaults() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        expectDefaults(mockMvc.perform(getSettings(user.accessToken()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)), "Asia/Ho_Chi_Minh");
    }

    @Test
    void patch_allFields_updatesSettingsAndProfile() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"theme": "KALE", "appearance": "DARK", "locale": "en", "timeZone": "Europe/London",
                         "startDay": 7, "nextWeek": 5, "weekendStartDay": 7, "dateFormat": "MDY", "timeFormat": "H12",
                         "startPage": "UPCOMING", "smartDateRecognition": false, "swipeLeft": "DELETE",
                         "swipeRight": "NONE", "navTabs": ["TODAY", "UPCOMING", "INBOX", "BROWSE"]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("KALE"))
                .andExpect(jsonPath("$.appearance").value("DARK"))
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.timeZone").value("Europe/London"))
                .andExpect(jsonPath("$.startDay").value(7))
                .andExpect(jsonPath("$.nextWeek").value(5))
                .andExpect(jsonPath("$.weekendStartDay").value(7))
                .andExpect(jsonPath("$.dateFormat").value("MDY"))
                .andExpect(jsonPath("$.timeFormat").value("H12"))
                .andExpect(jsonPath("$.startPage").value("UPCOMING"))
                .andExpect(jsonPath("$.smartDateRecognition").value(false))
                .andExpect(jsonPath("$.swipeLeft").value("DELETE"))
                .andExpect(jsonPath("$.swipeRight").value("NONE"))
                .andExpect(jsonPath("$.navTabs", contains("TODAY", "UPCOMING", "INBOX", "BROWSE")));

        mockMvc.perform(getSettings(user.accessToken()))
                .andExpect(jsonPath("$.theme").value("KALE"))
                .andExpect(jsonPath("$.navTabs", contains("TODAY", "UPCOMING", "INBOX", "BROWSE")));
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(jsonPath("$.locale").value("en"))
                .andExpect(jsonPath("$.timeZone").value("Europe/London"));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select theme, appearance, nav_tabs from users where id = ?", user.id());
        assertThat(row).containsEntry("theme", "KALE").containsEntry("appearance", "DARK")
                .containsEntry("nav_tabs", "TODAY,UPCOMING,INBOX,BROWSE");
    }

    @Test
    void patch_onlyAppearance_keepsOtherFields() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"appearance": "LIGHT", "navTabs": null}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appearance").value("LIGHT"))
                .andExpect(jsonPath("$.theme").value("TASKLY"))
                .andExpect(jsonPath("$.locale").value("vi"))
                .andExpect(jsonPath("$.swipeRight").value("COMPLETE"))
                .andExpect(jsonPath("$.navTabs", contains("INBOX", "TODAY", "UPCOMING", "BROWSE")));
    }

    @Test
    void patch_withoutRealChange_keepsUpdatedAt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        Instant before = updatedAt(user.id());
        clock.advance(Duration.ofMinutes(1));

        mockMvc.perform(patchSettings(user.accessToken(), "{}")).andExpect(status().isOk());
        mockMvc.perform(patchSettings(user.accessToken(), DEFAULTS_JSON)).andExpect(status().isOk());
        assertThat(updatedAt(user.id())).isEqualTo(before);

        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"swipeLeft": "SELECT"}
                        """))
                .andExpect(status().isOk());
        assertThat(updatedAt(user.id())).isAfter(before);
    }

    @Test
    void patch_invalidValues_returns400PerFieldAndChangesNothing() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"theme": "PURPLE", "appearance": "SYSTEM", "locale": "fr", "timeZone": "Mars/Base",
                         "startDay": 0, "nextWeek": 8, "weekendStartDay": -1, "dateFormat": "YMD", "timeFormat": "24h",
                         "startPage": "SEARCH", "swipeLeft": "ARCHIVE", "swipeRight": "complete",
                         "navTabs": ["INBOX", "BROWSE"]}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("theme", "appearance", "locale",
                        "timeZone", "startDay", "nextWeek", "weekendStartDay", "dateFormat", "timeFormat", "startPage",
                        "swipeLeft", "swipeRight", "navTabs")))
                .andExpect(jsonPath("$.errors[?(@.field == 'theme')].message", contains(
                        "Theme chỉ nhận TASKLY, MOONSTONE, TANGERINE, KALE, BLUEBERRY, LAVENDER hoặc RASPBERRY")))
                .andExpect(jsonPath("$.errors[?(@.field == 'appearance')].message",
                        contains("Chế độ sáng / tối chỉ nhận AUTO, LIGHT hoặc DARK")))
                .andExpect(jsonPath("$.errors[?(@.field == 'startDay')].message",
                        contains("Ngày trong tuần phải từ 1 (Thứ Hai) đến 7 (Chủ nhật)")))
                .andExpect(jsonPath("$.errors[?(@.field == 'startPage')].message",
                        contains("Trang mở đầu không hợp lệ")))
                .andExpect(jsonPath("$.errors[?(@.field == 'navTabs')].message", contains(
                        "Thanh tab phải có 3 đến 5 mục khác nhau, BROWSE đứng cuối và tối đa một mục ghim")))
                .andExpect(jsonPath("$.errors[?(@.field == 'swipeLeft')].message", contains(
                        "Hành động vuốt chỉ nhận COMPLETE, SCHEDULE, DELETE, REMINDERS, SELECT hoặc NONE")));

        expectDefaults(mockMvc.perform(getSettings(user.accessToken())), "Asia/Ho_Chi_Minh");
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIGHT", "DARK", "SYSTEM", "taskly"})
    void patch_legacyOrMiscasedThemeValues_areRejected(String theme) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"theme": "%s"}
                        """.formatted(theme)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", contains("theme")));
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

    @ParameterizedTest
    @ValueSource(strings = {
            "[\"INBOX\", \"BROWSE\"]",
            "[\"INBOX\", \"TODAY\", \"UPCOMING\", \"SEARCH\", \"FILTERS_LABELS\", \"BROWSE\"]",
            "[\"BROWSE\", \"INBOX\", \"TODAY\"]",
            "[\"INBOX\", \"TODAY\", \"UPCOMING\"]",
            "[\"INBOX\", \"INBOX\", \"BROWSE\"]",
            "[\"project:3f2b8c1e-7d4a-4b6e-9c1d-2a5e8f0b7c3d\", \"label:0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d\", \"BROWSE\"]",
            "[\"HOME\", \"TODAY\", \"BROWSE\"]",
            "[]",
            "[\"INBOX\", null, \"BROWSE\"]"})
    void patch_invalidNavTabs_returns400(String navTabs) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchSettings(user.accessToken(), "{\"navTabs\": " + navTabs + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'navTabs')].message", contains(
                        "Thanh tab phải có 3 đến 5 mục khác nhau, BROWSE đứng cuối và tối đa một mục ghim")));
    }

    @Test
    void patch_pinnedOwnProject_isAcceptedInStartPageAndNavTabs() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String pinned = "project:" + insertProject(user.id());

        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"startPage": "%s", "navTabs": ["TODAY", "%s", "BROWSE"]}
                        """.formatted(pinned, pinned)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startPage").value(pinned))
                .andExpect(jsonPath("$.navTabs", contains("TODAY", pinned, "BROWSE")));
    }

    @Test
    void patch_wrongCaseEnumValues_returns400() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"theme": "kale", "appearance": "dark", "locale": "EN", "dateFormat": "dmy"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field",
                        containsInAnyOrder("theme", "appearance", "locale", "dateFormat")));
    }

    @Test
    void reset_restoresDefaultsButKeepsTimeZone() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        mockMvc.perform(patchSettings(user.accessToken(), """
                        {"theme": "LAVENDER", "appearance": "DARK", "locale": "en", "timeZone": "Europe/London",
                         "startDay": 7, "dateFormat": "MDY", "startPage": "INBOX", "smartDateRecognition": false,
                         "swipeRight": "DELETE", "navTabs": ["UPCOMING", "TODAY", "BROWSE"]}
                        """))
                .andExpect(status().isOk());

        expectDefaults(mockMvc.perform(resetSettings(user.accessToken())).andExpect(status().isOk()),
                "Europe/London");
        expectDefaults(mockMvc.perform(getSettings(user.accessToken())), "Europe/London");
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
                        {"theme": "PURPLE", "appearance": "SYSTEM"}
                        """).header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'theme')].message", contains(
                        "Theme must be one of TASKLY, MOONSTONE, TANGERINE, KALE, BLUEBERRY, LAVENDER or RASPBERRY")))
                .andExpect(jsonPath("$.errors[?(@.field == 'appearance')].message",
                        contains("Appearance must be AUTO, LIGHT or DARK")));
    }
}
