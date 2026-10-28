package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * DND: POST reorder {type: TASK_DAY} — thứ tự trong NGÀY (day order kiểu Todoist) cho Today / Upcoming khi sort = date.
 * Vùng chứa = các task mở có cùng due_day (mọi dự án đọc được); afterId khác ngày → 400; chưa kéo lần nào → thứ tự cũ.
 */
class DayOrderApiTest extends IntegrationTestBase {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 23);

    private RegisteredUser user;
    private UUID project;

    @BeforeEach
    void data() throws Exception {
        clock.freezeAt(Instant.parse("2026-10-23T03:00:00Z")); // 10:00 ngày 23 giờ Việt Nam
        user = registerUser(uniqueEmail());
        project = testData.project(user.id(), "P");
    }

    private ResultActions dayReorder(UUID id, UUID afterId) throws Exception {
        String after = afterId == null ? "null" : "\"" + afterId + "\"";
        return authPost(user.accessToken(), "/api/v1/reorder", """
                {"type": "TASK_DAY", "id": "%s", "afterId": %s}
                """.formatted(id, after));
    }

    @Test
    void today_reorderAcrossProjectsAndTimes_keepsVersion() throws Exception {
        UUID other = testData.project(user.id(), "Q");
        UUID a = testData.insert(TaskRow.of(user.id(), project, "a").dueDate(DAY).sortOrder(1024));
        UUID b = testData.insert(TaskRow.of(user.id(), other, "b").dueDatetime(LocalDateTime.of(2026, 10, 23, 9, 0)));
        UUID c = testData.insert(TaskRow.of(user.id(), project, "c").dueDate(DAY).sortOrder(2048));
        authGet(user.accessToken(), "/api/v1/tasks?view=today").andExpect(jsonPath("$.items[*].content", contains("a", "c", "b")));

        dayReorder(b, null).andExpect(status().isOk()).andExpect(jsonPath("$.type").value("TASK_DAY"));
        authGet(user.accessToken(), "/api/v1/tasks?view=today").andExpect(jsonPath("$.items[*].content", contains("b", "a", "c")));

        dayReorder(a, c).andExpect(status().isOk());
        authGet(user.accessToken(), "/api/v1/tasks?view=today").andExpect(jsonPath("$.items[*].content", contains("b", "c", "a")));
        // app luôn gửi tham số Display (đường M2 / DisplaySql): cùng thứ tự; sort khác (priority) thì không theo day order
        authGet(user.accessToken(), "/api/v1/tasks?view=today&sort=date&order=asc&group=none")
                .andExpect(jsonPath("$.items[*].content", contains("b", "c", "a")));
        authGet(user.accessToken(), "/api/v1/tasks?view=upcoming&sort=date&order=asc&group=none")
                .andExpect(jsonPath("$.items[*].content", contains("b", "c", "a")));
        assertThat(testData.taskVersion(a)).isZero(); // native UPDATE như reorder: không tăng version
        assertThat(testData.taskVersion(b)).isZero();
    }

    @Test
    void afterIdOnAnotherDay_is400_andTaskWithoutDue_is400() throws Exception {
        UUID a = testData.insert(TaskRow.of(user.id(), project, "a").dueDate(DAY));
        UUID tomorrow = testData.insert(TaskRow.of(user.id(), project, "mai").dueDate(DAY.plusDays(1)));
        UUID noDue = testData.task(user.id(), project, "không hạn");

        dayReorder(a, tomorrow).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("afterId phải cùng vùng chứa"));
        dayReorder(noDue, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Công việc chưa có ngày hạn, không xếp thứ tự trong ngày được"));
    }

    @Test
    void movingToAnotherDay_dropsTheOldDayOrder() throws Exception {
        // tên riêng của test này (vài test khác đếm task theo nội dung trên DB dùng chung)
        UUID x = testData.insert(TaskRow.of(user.id(), project, "dnd-x").dueDate(DAY).sortOrder(10));
        testData.insert(TaskRow.of(user.id(), project, "dnd-x2").dueDate(DAY).sortOrder(20));
        UUID y = testData.insert(TaskRow.of(user.id(), project, "dnd-y").dueDate(DAY.plusDays(1)).sortOrder(500));
        UUID z = testData.insert(TaskRow.of(user.id(), project, "dnd-z").dueDate(DAY.plusDays(1)).sortOrder(600));
        dayReorder(z, null).andExpect(status().isOk()); // ngày 24: z, y
        dayReorder(x, null).andExpect(status().isOk()); // ngày 23: x, x2

        authGet(user.accessToken(), "/api/v1/tasks/" + x).andExpect(status().isOk());
        long version = testData.taskVersion(x);
        authPatch(user.accessToken(), "/api/v1/tasks/" + x,
                "{\"version\": " + version + ", \"dueDate\": \"2026-10-24\"}").andExpect(status().isOk());

        // x sang ngày 24: thứ tự của ngày 23 không còn hiệu lực → x đứng sau các task đã xếp của ngày 24
        authGet(user.accessToken(), "/api/v1/tasks?view=upcoming&from=2026-10-24")
                .andExpect(jsonPath("$.items[?(@.dueDate == '2026-10-24')].content", contains("dnd-z", "dnd-y", "dnd-x")));
        assertThat(y).isNotNull();
    }
}
