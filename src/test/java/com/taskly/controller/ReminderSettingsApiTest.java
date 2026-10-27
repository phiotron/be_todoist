package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
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

/** Cài đặt nhắc việc trong me/settings (spec 12.1.1, M4). */
class ReminderSettingsApiTest extends IntegrationTestBase {

    private static final String SETTINGS = "/api/v1/me/settings";
    private static final String MINUTES_VI =
            "Nhắc tự động chỉ nhận 0, 5, 10, 15, 30, 45, 60, 120, 180 hoặc 1440 phút";
    private static final String MINUTES_EN =
            "Automatic reminder must be 0, 5, 10, 15, 30, 45, 60, 120, 180 or 1440 minutes";

    private Instant updatedAt(UUID userId) {
        return jdbcTemplate.queryForObject("select updated_at from users where id = ?", Timestamp.class, userId)
                .toInstant();
    }

    @Test
    void get_newAccount_hasReminderDefaults() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authGet(user.accessToken(), SETTINGS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remindersEnabled").value(true))
                .andExpect(jsonPath("$.autoReminderMinutes").value(30));
    }

    @Test
    void patch_bothFields_updatesAndPersists() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"remindersEnabled": false, "autoReminderMinutes": 60}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remindersEnabled").value(false))
                .andExpect(jsonPath("$.autoReminderMinutes").value(60));

        authGet(user.accessToken(), SETTINGS)
                .andExpect(jsonPath("$.remindersEnabled").value(false))
                .andExpect(jsonPath("$.autoReminderMinutes").value(60));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select reminders_enabled, auto_reminder_minutes from users where id = ?", user.id());
        assertThat(row).containsEntry("reminders_enabled", false).containsEntry("auto_reminder_minutes", 60);
    }

    @Test
    void patch_nullAutoReminderMinutes_turnsAutoReminderOff() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"autoReminderMinutes": null}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.autoReminderMinutes").value(nullValue()))
                .andExpect(jsonPath("$.remindersEnabled").value(true));

        authGet(user.accessToken(), SETTINGS).andExpect(jsonPath("$.autoReminderMinutes").value(nullValue()));
        assertThat(jdbcTemplate.queryForObject(
                "select auto_reminder_minutes from users where id = ?", Integer.class, user.id())).isNull();
    }

    @Test
    void patch_withoutReminderFields_keepsThem() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPatch(user.accessToken(), SETTINGS, """
                {"remindersEnabled": false, "autoReminderMinutes": 120}
                """).andExpect(status().isOk());

        authPatch(user.accessToken(), SETTINGS, """
                {"timeZone": "Europe/London"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remindersEnabled").value(false))
                .andExpect(jsonPath("$.autoReminderMinutes").value(120));
    }

    @Test
    void patch_nullKeepsEveryOtherFieldButTurnsAutoReminderOff() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPatch(user.accessToken(), SETTINGS, """
                {"remindersEnabled": false, "autoReminderMinutes": 120, "startDay": 3}
                """).andExpect(status().isOk());

        // Quy ước M3: null = giữ nguyên (theme, startDay, remindersEnabled); riêng autoReminderMinutes: null = tắt.
        authPatch(user.accessToken(), SETTINGS, """
                {"theme": null, "startDay": null, "remindersEnabled": null, "autoReminderMinutes": null}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.theme").value("TASKLY"))
                .andExpect(jsonPath("$.startDay").value(3))
                .andExpect(jsonPath("$.remindersEnabled").value(false))
                .andExpect(jsonPath("$.autoReminderMinutes").value(nullValue()));
    }

    @Test
    void patch_sameValues_keepsUpdatedAt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        Instant before = updatedAt(user.id());
        clock.advance(Duration.ofMinutes(1));

        authPatch(user.accessToken(), SETTINGS, """
                {"remindersEnabled": true, "autoReminderMinutes": 30}
                """).andExpect(status().isOk());

        assertThat(updatedAt(user.id())).isEqualTo(before);
    }

    /** 400 có errors[] — không phải 500 UnexpectedTypeException: khoá việc constraint nằm ở kiểu phần tử của Optional. */
    @ParameterizedTest
    @ValueSource(ints = {-5, 1, 7, 20, 90, 1441})
    void patch_rejectsMinutesOutsideTheList(int minutes) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"autoReminderMinutes": %d}
                """.formatted(minutes))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("autoReminderMinutes"))
                .andExpect(jsonPath("$.errors[0].message").value(MINUTES_VI));
        authGet(user.accessToken(), SETTINGS).andExpect(jsonPath("$.autoReminderMinutes").value(30));
    }

    @Test
    void patch_rejectsNonNumericMinutes() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"autoReminderMinutes": "ten"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void validationMessageIsTranslatedToEnglish() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patch(SETTINGS)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"autoReminderMinutes": 25}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(MINUTES_EN));
    }

    @Test
    void reset_restoresReminderDefaultsAndKeepsTimeZone() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPatch(user.accessToken(), SETTINGS, """
                {"remindersEnabled": false, "autoReminderMinutes": null, "timeZone": "Europe/London"}
                """).andExpect(status().isOk());

        authPost(user.accessToken(), SETTINGS + "/reset")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remindersEnabled").value(true))
                .andExpect(jsonPath("$.autoReminderMinutes").value(30))
                .andExpect(jsonPath("$.timeZone").value("Europe/London"));
    }
}
