package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.security.JwtTokenIssuer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Cài đặt Productivity trong me/settings (M10, contract mục 2). */
class ProductivitySettingsApiTest extends IntegrationTestBase {

    private static final String SETTINGS = "/api/v1/me/settings";

    /** Token mới sau khi dời đồng hồ nhiều ngày (access token sống 15 phút theo cùng Clock). */
    @Autowired
    private JwtTokenIssuer tokens;

    @Test
    void get_newAccount_hasProductivityDefaults() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authGet(user.accessToken(), SETTINGS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyGoal").value(5))
                .andExpect(jsonPath("$.weeklyGoal").value(30))
                .andExpect(jsonPath("$.daysOff", contains(6, 7)))
                .andExpect(jsonPath("$.vacationMode").value(false))
                .andExpect(jsonPath("$.goalCelebrations").value(true))
                .andExpect(jsonPath("$.karmaEnabled").value(true));
    }

    @Test
    void patch_updatesEveryProductivityField() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"dailyGoal": 8, "weeklyGoal": 40, "daysOff": [7, 3], "goalCelebrations": false, "karmaEnabled": false}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyGoal").value(8))
                .andExpect(jsonPath("$.weeklyGoal").value(40))
                .andExpect(jsonPath("$.daysOff", contains(3, 7)))
                .andExpect(jsonPath("$.goalCelebrations").value(false))
                .andExpect(jsonPath("$.karmaEnabled").value(false));

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select daily_goal, weekly_goal, days_off, goal_celebrations, karma_enabled from users where id = ?",
                user.id());
        assertThat(row).containsEntry("daily_goal", 8).containsEntry("weekly_goal", 40)
                .containsEntry("days_off", "3,7").containsEntry("goal_celebrations", false)
                .containsEntry("karma_enabled", false);
    }

    @Test
    void patch_emptyDaysOff_meansNoDayOff() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"daysOff": []}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.daysOff", empty()));
        authGet(user.accessToken(), SETTINGS).andExpect(jsonPath("$.daysOff", empty()));
    }

    @Test
    void patch_otherFieldsKeepProductivitySettings() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPatch(user.accessToken(), SETTINGS, """
                {"dailyGoal": 3, "daysOff": [1]}
                """).andExpect(status().isOk());

        authPatch(user.accessToken(), SETTINGS, """
                {"theme": "KALE", "dailyGoal": null, "daysOff": null}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyGoal").value(3))
                .andExpect(jsonPath("$.daysOff", contains(1)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 201})
    void patch_rejectsDailyGoalOutOfRange(int goal) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"dailyGoal": %d}
                """.formatted(goal))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("dailyGoal"))
                .andExpect(jsonPath("$.errors[0].message").value("Mục tiêu ngày phải từ 1 đến 200 công việc"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1001})
    void patch_rejectsWeeklyGoalOutOfRange(int goal) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"weeklyGoal": %d}
                """.formatted(goal))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("weeklyGoal"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[0]", "[8]", "[1, 1]", "[1,2,3,4,5,6,7]", "[null]"})
    void patch_rejectsInvalidDaysOff(String daysOff) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"daysOff": %s}
                """.formatted(daysOff))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("daysOff"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("Ngày nghỉ phải là các ngày 1–7 khác nhau, nghỉ tối đa 6 ngày"));
        authGet(user.accessToken(), SETTINGS).andExpect(jsonPath("$.daysOff", contains(6, 7)));
    }

    @Test
    void validationMessagesAreTranslatedToEnglish() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patch(SETTINGS)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"weeklyGoal": 0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Weekly goal must be between 1 and 1000 tasks"));
    }

    @Test
    void vacationMode_onOpensOnePeriod_offClosesIt() throws Exception {
        clock.freezeAt(Instant.parse("2026-11-02T03:00:00Z")); // 10:00 ngày 02/11 giờ Việt Nam
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"vacationMode": true}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.vacationMode").value(true));
        authPatch(user.accessToken(), SETTINGS, """
                {"vacationMode": true}
                """).andExpect(status().isOk());

        List<Map<String, Object>> periods = jdbcTemplate.queryForList(
                "select started_on, ended_on from vacation_periods where user_id = ?", user.id());
        assertThat(periods).hasSize(1);
        assertThat(periods.get(0).get("started_on").toString()).isEqualTo("2026-11-02");
        assertThat(periods.get(0).get("ended_on")).isNull();

        clock.advance(Duration.ofDays(3));
        authPatch(tokens.issueAccessToken(user.id()), SETTINGS, """
                {"vacationMode": false}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.vacationMode").value(false));

        Map<String, Object> closed = jdbcTemplate.queryForMap(
                "select started_on, ended_on from vacation_periods where user_id = ?", user.id());
        assertThat(closed.get("ended_on").toString()).isEqualTo(LocalDate.of(2026, 11, 5).toString());
    }

    @Test
    void vacationMode_offWhenAlreadyOff_createsNothing() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"vacationMode": false}
                """).andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from vacation_periods where user_id = ?", Integer.class, user.id())).isZero();
    }

    @Test
    void reset_keepsProductivitySettings() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPatch(user.accessToken(), SETTINGS, """
                {"dailyGoal": 9, "weeklyGoal": 50, "karmaEnabled": false}
                """).andExpect(status().isOk());

        authPost(user.accessToken(), SETTINGS + "/reset")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyGoal").value(9))
                .andExpect(jsonPath("$.weeklyGoal").value(50))
                .andExpect(jsonPath("$.karmaEnabled").value(false));
    }
}
