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

/** me/settings M12 (contract mục 2): snoozeMinutes (mặc định 15, chờ duyệt), pinReminders, urgentDeviceId. */
class M12SettingsApiTest extends IntegrationTestBase {

    private static final String SETTINGS = "/api/v1/me/settings";
    private static final String DEVICE = "5b0d6c3e-9a4f-4f6e-8a51-2f2d7e4c1b90";
    private static final String SNOOZE_VI = "Thời gian hoãn chỉ nhận 5, 10, 15, 30, 45, 60, 120 hoặc 180 phút";
    private static final String SNOOZE_EN = "Snooze must be 5, 10, 15, 30, 45, 60, 120 or 180 minutes";
    private static final String DEVICE_VI = "Mã thiết bị không hợp lệ";

    private Instant updatedAt(UUID userId) {
        return jdbcTemplate.queryForObject("select updated_at from users where id = ?", Timestamp.class, userId)
                .toInstant();
    }

    @Test
    void get_newAccount_hasM12Defaults() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authGet(user.accessToken(), SETTINGS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snoozeMinutes").value(15))
                .andExpect(jsonPath("$.pinReminders").value(false))
                .andExpect(jsonPath("$.urgentDeviceId").value(nullValue()));
    }

    @Test
    void patch_allThreeFields_updatesAndPersists() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"snoozeMinutes": 30, "pinReminders": true, "urgentDeviceId": "%s"}
                """.formatted(DEVICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snoozeMinutes").value(30))
                .andExpect(jsonPath("$.pinReminders").value(true))
                .andExpect(jsonPath("$.urgentDeviceId").value(DEVICE));

        authGet(user.accessToken(), SETTINGS)
                .andExpect(jsonPath("$.snoozeMinutes").value(30))
                .andExpect(jsonPath("$.pinReminders").value(true))
                .andExpect(jsonPath("$.urgentDeviceId").value(DEVICE));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select snooze_minutes, pin_reminders, urgent_device_id from users where id = ?", user.id());
        assertThat(row).containsEntry("snooze_minutes", 30).containsEntry("pin_reminders", true)
                .containsEntry("urgent_device_id", DEVICE);
    }

    @Test
    void patch_absentFieldsKeepValues_nullDeviceClearsIt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPatch(user.accessToken(), SETTINGS, """
                {"snoozeMinutes": 60, "pinReminders": true, "urgentDeviceId": "%s"}
                """.formatted(DEVICE)).andExpect(status().isOk());

        authPatch(user.accessToken(), SETTINGS, """
                {"timeZone": "Europe/London"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snoozeMinutes").value(60))
                .andExpect(jsonPath("$.pinReminders").value(true))
                .andExpect(jsonPath("$.urgentDeviceId").value(DEVICE));

        // null = giữ với snoozeMinutes / pinReminders (quy ước M3); riêng urgentDeviceId: null = bỏ chọn thiết bị.
        authPatch(user.accessToken(), SETTINGS, """
                {"snoozeMinutes": null, "pinReminders": null, "urgentDeviceId": null}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snoozeMinutes").value(60))
                .andExpect(jsonPath("$.pinReminders").value(true))
                .andExpect(jsonPath("$.urgentDeviceId").value(nullValue()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 14, 20, 90, 240, 1440})
    void patch_rejectsSnoozeOutsideTheList(int minutes) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"snoozeMinutes": %d}
                """.formatted(minutes))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("snoozeMinutes"))
                .andExpect(jsonPath("$.errors[0].message").value(SNOOZE_VI));
        authGet(user.accessToken(), SETTINGS).andExpect(jsonPath("$.snoozeMinutes").value(15));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "short", "has space in it", "ký-tự-lạ-12345",
            "0123456789012345678901234567890123456789012345678901234567890123456789"})
    void patch_rejectsMalformedDeviceId(String deviceId) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPatch(user.accessToken(), SETTINGS, """
                {"urgentDeviceId": "%s"}
                """.formatted(deviceId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("urgentDeviceId"))
                .andExpect(jsonPath("$.errors[0].message").value(DEVICE_VI));
        authGet(user.accessToken(), SETTINGS).andExpect(jsonPath("$.urgentDeviceId").value(nullValue()));
    }

    @Test
    void snoozeValidationIsTranslatedToEnglish() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(patch(SETTINGS)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"snoozeMinutes": 25}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value(SNOOZE_EN));
    }

    @Test
    void patch_sameValues_keepsUpdatedAt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        Instant before = updatedAt(user.id());
        clock.advance(Duration.ofMinutes(1));

        authPatch(user.accessToken(), SETTINGS, """
                {"snoozeMinutes": 15, "pinReminders": false, "urgentDeviceId": null}
                """).andExpect(status().isOk());

        assertThat(updatedAt(user.id())).isEqualTo(before);
    }

    @Test
    void reset_restoresM12Defaults() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPatch(user.accessToken(), SETTINGS, """
                {"snoozeMinutes": 120, "pinReminders": true, "urgentDeviceId": "%s"}
                """.formatted(DEVICE)).andExpect(status().isOk());

        authPost(user.accessToken(), SETTINGS + "/reset")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snoozeMinutes").value(15))
                .andExpect(jsonPath("$.pinReminders").value(false))
                .andExpect(jsonPath("$.urgentDeviceId").value(nullValue()));
    }
}
