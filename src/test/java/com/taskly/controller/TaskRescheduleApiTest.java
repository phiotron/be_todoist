package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
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

/**
 * POST tasks/overdue/reschedule (spec 12.5): dời MỌI task trong khối Quá hạn (due_day < hôm nay, chưa xong, không tính
 * dự án lưu trữ) của user sang date ≥ hôm nay; task có giờ giữ giờ + duration; mỗi task đổi tăng version.
 */
class TaskRescheduleApiTest extends IntegrationTestBase {

    private static final String RESCHEDULE = "/api/v1/tasks/overdue/reschedule";

    private RegisteredUser user;
    private UUID project;
    private UUID overdueDate;
    private UUID overdueTime;
    private UUID today;
    private UUID laterToday;
    private UUID noDue;

    @BeforeEach
    void data() throws Exception {
        clock.freezeAt(Instant.parse("2026-10-23T03:00:00Z")); // 10:00 ngày 23 giờ Việt Nam
        user = registerUser(uniqueEmail());
        project = testData.project(user.id(), "P");
        overdueDate = testData.insert(TaskRow.of(user.id(), project, "Quá hạn ngày")
                .dueDate(LocalDate.of(2026, 10, 20)));
        overdueTime = testData.insert(TaskRow.of(user.id(), project, "Quá hạn giờ")
                .dueDatetime(LocalDateTime.of(2026, 10, 21, 9, 15)).durationMinutes(30));
        today = testData.insert(TaskRow.of(user.id(), project, "Hôm nay").dueDate(LocalDate.of(2026, 10, 23)));
        laterToday = testData.insert(TaskRow.of(user.id(), project, "Chiều nay")
                .dueDatetime(LocalDateTime.of(2026, 10, 23, 13, 0)));
        noDue = testData.task(user.id(), project, "Không hạn");
    }

    @Test
    void reschedulesAllOverdueOpenTasks_keepingTimeAndDuration_andNothingElse() throws Exception {
        UUID done = testData.insert(TaskRow.of(user.id(), project, "Xong").dueDate(LocalDate.of(2026, 10, 1))
                .completedAt(clock.instant()));
        UUID archivedProject = testData.project(user.id(), "Lưu trữ");
        UUID inArchived = testData.insert(TaskRow.of(user.id(), archivedProject, "Trong dự án lưu trữ")
                .dueDate(LocalDate.of(2026, 10, 1)));
        testData.archiveProject(archivedProject);
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID bobsOverdue = testData.insert(TaskRow.of(bob.id(), testData.inboxOf(bob.id()), "Của Bob")
                .dueDate(LocalDate.of(2026, 10, 1)));

        authPost(user.accessToken(), RESCHEDULE, "{\"date\": \"2026-10-25\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskIds",
                        containsInAnyOrder(overdueDate.toString(), overdueTime.toString())));

        authGet(user.accessToken(), "/api/v1/tasks/" + overdueDate).andExpect(jsonPath("$.dueDate").value("2026-10-25"));
        authGet(user.accessToken(), "/api/v1/tasks/" + overdueTime)
                .andExpect(jsonPath("$.dueDatetime").value("2026-10-25T09:15:00"))
                .andExpect(jsonPath("$.durationMinutes").value(30))
                .andExpect(jsonPath("$.isOverdue").value(false));
        authGet(user.accessToken(), "/api/v1/tasks/" + today).andExpect(jsonPath("$.dueDate").value("2026-10-23"));
        authGet(user.accessToken(), "/api/v1/tasks/" + laterToday)
                .andExpect(jsonPath("$.dueDatetime").value("2026-10-23T13:00:00"));
        authGet(user.accessToken(), "/api/v1/tasks/" + noDue).andExpect(jsonPath("$.dueDate").value(nullValue()));
        authGet(user.accessToken(), "/api/v1/tasks/" + done).andExpect(jsonPath("$.dueDate").value("2026-10-01"));
        authGet(user.accessToken(), "/api/v1/tasks/" + inArchived).andExpect(jsonPath("$.dueDate").value("2026-10-01"));
        authGet(bob.accessToken(), "/api/v1/tasks/" + bobsOverdue).andExpect(jsonPath("$.dueDate").value("2026-10-01"));
        authGet(user.accessToken(), "/api/v1/tasks?view=overdue").andExpect(jsonPath("$.items", empty()));
        assertThat(testData.taskVersion(overdueDate)).isEqualTo(1); // đổi nội dung (due) → version + 1
        assertThat(testData.taskVersion(overdueTime)).isEqualTo(1);
        assertThat(testData.taskVersion(today)).isZero();
    }

    @Test
    void reschedulingToToday_keepsThePastTimeRed_inTheTodayBlock_notInTheOverdueBlock() throws Exception {
        // 10:00 ngày 23: "Quá hạn giờ" (21/10 09:15) dời sang hôm nay → 23/10 09:15 đã qua → đỏ, nhưng ở khối Hôm nay
        authPost(user.accessToken(), RESCHEDULE, "{\"date\": \"2026-10-23\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskIds", containsInAnyOrder(overdueDate.toString(), overdueTime.toString())));

        authGet(user.accessToken(), "/api/v1/tasks/" + overdueTime)
                .andExpect(jsonPath("$.dueDatetime").value("2026-10-23T09:15:00"))
                .andExpect(jsonPath("$.isOverdue").value(true));
        authGet(user.accessToken(), "/api/v1/tasks?view=overdue").andExpect(jsonPath("$.items", empty()));
        authGet(user.accessToken(), "/api/v1/tasks?view=today")
                .andExpect(jsonPath("$.items[?(@.content == 'Quá hạn giờ')].isOverdue", contains(true)));
    }

    @Test
    void dateBeforeToday_is400_andNothingChanges() throws Exception {
        authPost(user.accessToken(), RESCHEDULE, "{\"date\": \"2026-10-22\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Ngày mới không được trước hôm nay"));
        authGet(user.accessToken(), "/api/v1/tasks/" + overdueDate).andExpect(jsonPath("$.dueDate").value("2026-10-20"));
        authPost(user.accessToken(), RESCHEDULE, "{\"date\": \"2026-10-23\"}").andExpect(status().isOk()); // đúng hôm nay: được
    }

    @Test
    void nothingOverdue_returnsEmptyList_andDateIsRequired() throws Exception {
        authPost(user.accessToken(), RESCHEDULE, "{\"date\": \"2026-10-25\"}").andExpect(status().isOk());
        authPost(user.accessToken(), RESCHEDULE, "{\"date\": \"2026-10-26\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskIds", empty()));
        authPost(user.accessToken(), RESCHEDULE, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Thiếu date"));
        assertThat(overdueDate).isNotNull();
    }
}
