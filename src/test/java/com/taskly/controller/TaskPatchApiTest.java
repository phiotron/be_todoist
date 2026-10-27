package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Bổ sung ngoài plan Task 15: giờ floating (spec 5.2) bị từ chối khi có Z / offset ở CẢ tạo lẫn PATCH (Jackson mặc
 * định nhận `…Z` và cắt Z), và `version` chỉ tăng khi nội dung thực sự đổi (spec 5.6, C4).
 */
class TaskPatchApiTest extends IntegrationTestBase {

    private static final String TASKS = "/api/v1/tasks";
    private static final Pattern FLOATING = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:00");

    private UUID create(RegisteredUser user, String json) throws Exception {
        return readUuid(authPost(user.accessToken(), TASKS, json).andExpect(status().isCreated()), "$.id");
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-10-25T02:00:00Z", "2026-10-25T02:00Z", "2026-10-25T02:00:00z",
            "2026-10-25T09:00:00+07:00", "2026-10-25T09:00:00-05:00", "2026-10-25T09:00:00.500",
            "2026-10-25", "2026-10-25T09", "25/10/2026 09:00", "", "09:00"})
    void dueDatetime_withZoneOrWrongShape_is400_onCreateAndPatch_andLeavesTaskUntouched(String value) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = create(user, "{\"content\": \"Hạn\", \"dueDatetime\": \"2026-10-25T09:00:00\"}");
        String path = TASKS + "/" + id;

        authPost(user.accessToken(), TASKS, "{\"content\": \"x\", \"dueDatetime\": \"%s\"}".formatted(value))
                .andExpect(status().isBadRequest());
        authPatch(user.accessToken(), path, "{\"version\": 0, \"dueDatetime\": \"%s\"}".formatted(value))
                .andExpect(status().isBadRequest());

        authGet(user.accessToken(), path)
                .andExpect(jsonPath("$.dueDatetime").value("2026-10-25T09:00:00"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void emptyStringDates_are400_neverSilentlyClearing() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = create(user, "{\"content\": \"Hạn\", \"dueDate\": \"2026-10-25\", \"deadlineDate\": \"2026-10-31\"}");
        String path = TASKS + "/" + id;

        authPatch(user.accessToken(), path, "{\"version\": 0, \"dueDate\": \"\"}").andExpect(status().isBadRequest());
        authPatch(user.accessToken(), path, "{\"version\": 0, \"deadlineDate\": \"\"}")
                .andExpect(status().isBadRequest());
        authPost(user.accessToken(), TASKS, "{\"content\": \"x\", \"dueDate\": \"\"}")
                .andExpect(status().isBadRequest());

        authGet(user.accessToken(), path)
                .andExpect(jsonPath("$.dueDate").value("2026-10-25"))
                .andExpect(jsonPath("$.deadlineDate").value("2026-10-31"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void dueDatetime_output_isAlwaysFloatingSecondsZero_neverZoneOrFraction() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        String created = readString(authPost(user.accessToken(), TASKS,
                "{\"content\": \"a\", \"dueDatetime\": \"2026-10-25T09:07\"}").andExpect(status().isCreated()),
                "$.dueDatetime");
        String patched = readString(authPatch(user.accessToken(), TASKS + "/" + create(user, "{\"content\": \"b\"}"),
                "{\"version\": 0, \"dueDatetime\": \"2026-10-26T23:59:58\"}").andExpect(status().isOk()),
                "$.dueDatetime");

        assertThat(created).isEqualTo("2026-10-25T09:07:00").matches(FLOATING);
        assertThat(patched).isEqualTo("2026-10-26T23:59:00").matches(FLOATING);
    }

    @Test
    void patch_sameValuesInAnyShape_keepVersionAndUpdatedAt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = create(user, """
                {"content": "Giữ", "description": "d", "priority": 2, "dueDatetime": "2026-10-25T09:00:00",
                 "durationMinutes": 30, "deadlineDate": "2026-10-31"}
                """);
        String path = TASKS + "/" + id;
        String updatedAt = readString(authGet(user.accessToken(), path), "$.updatedAt");
        clock.advance(Duration.ofMinutes(5));

        String[] bodies = {
                "{\"version\": 0}",
                "{\"version\": 0, \"content\": \"  Giữ  \"}", // content được strip → như cũ
                "{\"version\": 0, \"dueDatetime\": \"2026-10-25T09:00:59\"}", // cắt về phút → như cũ
                "{\"version\": 0, \"dueDatetime\": \"2026-10-25T09:00\", \"durationMinutes\": 30}",
                "{\"version\": 0, \"durationMinutes\": 30, \"deadlineDate\": \"2026-10-31\"}",
                "{\"version\": 0, \"description\": \"d\", \"priority\": 2}"
        };
        for (String body : bodies) {
            authPatch(user.accessToken(), path, body)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.version").value(0))
                    .andExpect(jsonPath("$.updatedAt").value(updatedAt));
        }
        assertThat(testData.taskVersion(id)).isZero();
    }

    @Test
    void patch_realChange_bumpsVersionByExactlyOne_andFailedPatchDoesNot() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = create(user, "{\"content\": \"Gốc\", \"dueDate\": \"2026-10-25\"}");
        String path = TASKS + "/" + id;

        authPatch(user.accessToken(), path, "{\"version\": 0, \"durationMinutes\": 30}")
                .andExpect(status().isBadRequest()); // duration không giờ
        authPatch(user.accessToken(), path, "{\"version\": 0, \"dueDate\": \"2026-10-26\", \"dueDatetime\": "
                + "\"2026-10-26T09:00\"}").andExpect(status().isBadRequest()); // cả hai due
        authPatch(user.accessToken(), path, "{\"version\": 0, \"content\": \"   \"}")
                .andExpect(status().isBadRequest());
        assertThat(testData.taskVersion(id)).isZero();

        authPatch(user.accessToken(), path, "{\"version\": 0, \"priority\": 1}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        authPatch(user.accessToken(), path, "{\"version\": 1, \"deadlineDate\": \"2026-11-30\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        assertThat(testData.taskVersion(id)).isEqualTo(2);
    }

    @Test
    void patch_dueNull_clearsWholeDueAndDuration_dueKeysAbsentKeepThem() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = create(user, "{\"content\": \"Hạn\", \"dueDatetime\": \"2026-10-25T09:00\", \"durationMinutes\": 60}");
        String path = TASKS + "/" + id;

        authPatch(user.accessToken(), path, "{\"version\": 0, \"priority\": 3}")
                .andExpect(jsonPath("$.dueDatetime").value("2026-10-25T09:00:00"))
                .andExpect(jsonPath("$.durationMinutes").value(60));
        // gửi dueDatetime: null = xoá toàn bộ due (cả ngày lẫn giờ) và duration đi theo
        authPatch(user.accessToken(), path, "{\"version\": 1, \"dueDatetime\": null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDatetime").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.dueDate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.durationMinutes").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.version").value(2));
    }
}
