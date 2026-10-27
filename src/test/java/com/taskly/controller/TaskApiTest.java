package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskApiTest extends IntegrationTestBase {

    private static final String TASKS = "/api/v1/tasks";

    private UUID createTask(RegisteredUser user, String json) throws Exception {
        return readUuid(authPost(user.accessToken(), TASKS, json).andExpect(status().isCreated()), "$.id");
    }

    @Test
    void create_minimal_goesToInboxWithDefaults_andHasNoFavoriteField() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());

        authPost(user.accessToken(), TASKS, """
                {"content": "  Mua sữa  "}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("Mua sữa"))
                .andExpect(jsonPath("$.description").value(""))
                .andExpect(jsonPath("$.priority").value(4))
                .andExpect(jsonPath("$.projectId").value(inbox.toString()))
                .andExpect(jsonPath("$.projectName").value("Inbox"))
                .andExpect(jsonPath("$.sectionId").value(nullValue()))
                .andExpect(jsonPath("$.parentId").value(nullValue()))
                .andExpect(jsonPath("$.parentContent").value(nullValue()))
                .andExpect(jsonPath("$.dueDate").value(nullValue()))
                .andExpect(jsonPath("$.dueDatetime").value(nullValue()))
                .andExpect(jsonPath("$.durationMinutes").value(nullValue()))
                .andExpect(jsonPath("$.deadlineDate").value(nullValue()))
                .andExpect(jsonPath("$.dueString").value(nullValue()))
                .andExpect(jsonPath("$.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.isOverdue").value(false))
                .andExpect(jsonPath("$.isFavorite").doesNotExist()) // bản 3: task không có yêu thích
                .andExpect(jsonPath("$.subtaskCount").value(0))
                .andExpect(jsonPath("$.completedSubtaskCount").value(0))
                .andExpect(jsonPath("$.sortOrder").value(1024))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void create_invalidFields_returns400() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        authPost(user.accessToken(), TASKS, """
                {"content": "   ", "priority": 5}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("content", "priority")))
                .andExpect(jsonPath("$.errors[?(@.field == 'content')].message")
                        .value(org.hamcrest.Matchers.contains("Tên công việc phải từ 1 đến 500 ký tự")))
                .andExpect(jsonPath("$.errors[?(@.field == 'priority')].message")
                        .value(org.hamcrest.Matchers.contains("Ưu tiên phải từ 1 đến 4")));
        authPost(user.accessToken(), TASKS, "{\"content\": \"%s\"}".formatted("a".repeat(501)))
                .andExpect(status().isBadRequest());
        authPost(user.accessToken(), TASKS, "{\"content\": \"x\", \"description\": \"%s\"}".formatted("d".repeat(16384)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("description"))
                .andExpect(jsonPath("$.errors[0].message").value("Mô tả tối đa 16383 ký tự"));
        authPost(user.accessToken(), TASKS, """
                {"content": "x", "dueDate": "2026-10-25", "dueDatetime": "2026-10-25T09:00:00"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Chỉ được đặt một trong dueDate hoặc dueDatetime"));
        authPost(user.accessToken(), TASKS, """
                {"content": "x", "dueDatetime": "2026-10-25T09:00:00", "durationMinutes": 1441}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Thời lượng phải từ 1 đến 1440 phút"));
        authPost(user.accessToken(), TASKS, """
                {"content": "x", "dueDate": "2026-10-25", "durationMinutes": 30}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Chỉ đặt được thời lượng cho công việc có giờ"));
        // giờ floating: không nhận Z / offset (quyết định Q32, spec 5.2)
        authPost(user.accessToken(), TASKS, """
                {"content": "x", "dueDatetime": "2026-10-25T02:00:00Z"}
                """).andExpect(status().isBadRequest());
        authPost(user.accessToken(), TASKS, """
                {"content": "x", "dueDatetime": "2026-10-25T09:00:00+07:00"}
                """).andExpect(status().isBadRequest());
    }

    @Test
    void create_floatingDueWithDurationAndDeadline_isTruncatedToMinutes_andIgnoresTimeZoneChange() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        UUID id = readUuid(authPost(user.accessToken(), TASKS, """
                {"content": "Họp", "dueDatetime": "2026-10-25T09:00:59", "durationMinutes": 45,
                 "deadlineDate": "2026-10-31"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dueDatetime").value("2026-10-25T09:00:00")) // xuất luôn yyyy-MM-ddTHH:mm:ss
                .andExpect(jsonPath("$.dueDate").value(nullValue()))
                .andExpect(jsonPath("$.durationMinutes").value(45))
                .andExpect(jsonPath("$.deadlineDate").value("2026-10-31")), "$.id");

        testData.setTimeZone(user.id(), "America/New_York");
        authGet(user.accessToken(), TASKS + "/" + id).andExpect(jsonPath("$.dueDatetime").value("2026-10-25T09:00:00"));
        // nhập chấp nhận HH:mm (không giây), cả khi tạo lẫn khi PATCH
        authPost(user.accessToken(), TASKS, """
                {"content": "Không giây", "dueDatetime": "2026-10-26T07:30"}
                """).andExpect(jsonPath("$.dueDatetime").value("2026-10-26T07:30:00"));
        authPatch(user.accessToken(), TASKS + "/" + id, "{\"version\": 0, \"dueDatetime\": \"2026-10-27T08:15\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDatetime").value("2026-10-27T08:15:00"));
    }

    @Test
    void create_subtask_inheritsProjectAndSectionFromParent_andParentShowsProgress() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID section = testData.section(project, "S");
        UUID parent = testData.insert(TaskRow.of(user.id(), project, "Cha").section(section));
        testData.insert(TaskRow.of(user.id(), project, "Con xong").section(section).parent(parent)
                .completedAt(clock.instant()));

        UUID child = readUuid(authPost(user.accessToken(), TASKS, """
                {"parentId": "%s", "content": "Con mới"}
                """.formatted(parent))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.projectId").value(project.toString()))
                .andExpect(jsonPath("$.parentId").value(parent.toString()))
                .andExpect(jsonPath("$.sectionId").value(section.toString()))
                .andExpect(jsonPath("$.parentContent").value("Cha")), "$.id");

        authGet(user.accessToken(), TASKS + "/" + parent)
                .andExpect(jsonPath("$.subtaskCount").value(2))
                .andExpect(jsonPath("$.completedSubtaskCount").value(1));
        assertThat(testData.taskWorkspace(child)).isEqualTo(testData.workspaceOf(user.id()));
    }

    @Test
    void create_afterId_placesBetweenSiblings() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID first = testData.insert(TaskRow.of(user.id(), project, "a").sortOrder(1024));
        testData.insert(TaskRow.of(user.id(), project, "b").sortOrder(2048));

        authPost(user.accessToken(), TASKS, """
                {"projectId": "%s", "content": "giữa", "afterId": "%s"}
                """.formatted(project, first))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sortOrder").value(1536));
    }

    @Test
    void create_afterIdAbsent_goesToEnd_afterIdNull_goesToTop() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        testData.insert(TaskRow.of(user.id(), project, "a").sortOrder(1024));
        testData.insert(TaskRow.of(user.id(), project, "b").sortOrder(2048));

        // vắng afterId = CUỐI vùng chứa (spec 12 đầu)
        authPost(user.accessToken(), TASKS, "{\"projectId\": \"%s\", \"content\": \"cuối\"}".formatted(project))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sortOrder").value(3072));
        // "afterId": null tường minh = ĐẦU vùng chứa
        authPost(user.accessToken(), TASKS, "{\"projectId\": \"%s\", \"content\": \"đầu\", \"afterId\": null}"
                .formatted(project))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sortOrder").value(512));
    }

    @Test
    void create_afterIdWithoutGap_renumbersNatively_withoutBumpingSiblingVersions() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID first = testData.insert(TaskRow.of(user.id(), project, "a").sortOrder(1024));
        UUID second = testData.insert(TaskRow.of(user.id(), project, "b").sortOrder(1025)); // không còn khoảng trống
        Instant updatedBefore = testData.taskUpdatedAt(second);
        clock.advance(Duration.ofMinutes(1));

        UUID created = readUuid(authPost(user.accessToken(), TASKS, """
                {"projectId": "%s", "content": "giữa", "afterId": "%s"}
                """.formatted(project, first))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sortOrder").value(2048)), "$.id");

        assertThat(testData.taskSortOrder(first)).isEqualTo(1024);
        assertThat(testData.taskSortOrder(second)).isEqualTo(3072); // đánh số lại cả vùng chứa
        assertThat(testData.taskSortOrder(created)).isEqualTo(2048);
        // sort_order đổi bằng native UPDATE (TaskService.writeSortOrders): không tăng version, vẫn ghi updated_at
        assertThat(testData.taskVersion(first)).isZero();
        assertThat(testData.taskVersion(second)).isZero();
        assertThat(testData.taskUpdatedAt(second)).isAfter(updatedBefore);
    }

    @Test
    void create_setsWorkspaceIdOfTheProject_forInboxAndRegularProject() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");

        UUID inInbox = createTask(user, "{\"content\": \"Inbox\"}");
        UUID inProject = createTask(user, "{\"projectId\": \"%s\", \"content\": \"Dự án\"}".formatted(project));

        assertThat(testData.taskWorkspace(inInbox)).isEqualTo(testData.workspaceOf(user.id()));
        assertThat(testData.taskWorkspace(inProject)).isEqualTo(testData.workspaceOf(user.id()));
    }

    @Test
    void patch_requiresVersion_andStaleVersionReturns409WithCurrentAndCode() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = createTask(user, "{\"content\": \"Gốc\"}");
        String path = TASKS + "/" + id;

        authPatch(user.accessToken(), path, "{\"content\": \"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Thiếu version"));
        authPatch(user.accessToken(), path, "{\"version\": 0, \"content\": \"Sửa ở máy A\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        authPatch(user.accessToken(), path, "{\"version\": 0, \"content\": \"Sửa ở máy B\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Đã có thay đổi ở nơi khác"))
                .andExpect(jsonPath("$.code").value("version-conflict"))
                .andExpect(jsonPath("$.current.version").value(1))
                .andExpect(jsonPath("$.current.content").value("Sửa ở máy A"));
    }

    @Test
    void patch_noChange_keepsVersionAndUpdatedAt() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = createTask(user, """
                {"content": "Giữ nguyên", "priority": 2, "dueDatetime": "2026-10-25T09:00:00", "durationMinutes": 30}
                """);
        String path = TASKS + "/" + id;
        String updatedAt = readString(authGet(user.accessToken(), path), "$.updatedAt");

        clock.advance(Duration.ofMinutes(3));
        authPatch(user.accessToken(), path, """
                {"version": 0, "content": "Giữ nguyên", "priority": 2, "dueDatetime": "2026-10-25T09:00:00",
                 "durationMinutes": 30}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.updatedAt").value(updatedAt));
    }

    @Test
    void patch_dueDurationDeadline_absentKeeps_nullClears_losingTimeClearsDuration() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID id = createTask(user, """
                {"content": "Hạn", "dueDatetime": "2026-10-25T09:00:00", "durationMinutes": 60}
                """);
        String path = TASKS + "/" + id;

        authPatch(user.accessToken(), path, "{\"version\": 0, \"content\": \"Hạn 2\"}")
                .andExpect(jsonPath("$.dueDatetime").value("2026-10-25T09:00:00"))
                .andExpect(jsonPath("$.durationMinutes").value(60));
        authPatch(user.accessToken(), path, "{\"version\": 1, \"dueDatetime\": \"2026-10-26T14:00:00\"}")
                .andExpect(jsonPath("$.dueDatetime").value("2026-10-26T14:00:00"))
                .andExpect(jsonPath("$.durationMinutes").value(60)); // còn giờ → giữ duration
        authPatch(user.accessToken(), path, "{\"version\": 2, \"dueDate\": \"2026-10-27\"}")
                .andExpect(jsonPath("$.dueDate").value("2026-10-27"))
                .andExpect(jsonPath("$.dueDatetime").value(nullValue()))
                .andExpect(jsonPath("$.durationMinutes").value(nullValue())); // mất giờ → server xoá duration
        authPatch(user.accessToken(), path, "{\"version\": 3, \"durationMinutes\": 30}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Chỉ đặt được thời lượng cho công việc có giờ"));
        authPatch(user.accessToken(), path, "{\"version\": 3, \"dueDate\": null, \"deadlineDate\": \"2026-11-01\"}")
                .andExpect(jsonPath("$.dueDate").value(nullValue()))
                .andExpect(jsonPath("$.deadlineDate").value("2026-11-01"))
                .andExpect(jsonPath("$.version").value(4));
        authPatch(user.accessToken(), path, "{\"version\": 4, \"deadlineDate\": null, \"description\": \"Ghi chú\"}")
                .andExpect(jsonPath("$.deadlineDate").value(nullValue()))
                .andExpect(jsonPath("$.description").value("Ghi chú"));
        authPatch(user.accessToken(), path, "{\"version\": 5, \"description\": \"\"}")
                .andExpect(jsonPath("$.description").value(""));
    }

    @Test
    void archivedProjectOrSection_taskIsReadOnly409_butReadable() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID section = testData.section(project, "S");
        UUID inSection = testData.insert(TaskRow.of(user.id(), project, "Trong section").section(section));
        UUID task = testData.task(user.id(), project, "T");
        testData.archiveSection(section);

        authGet(user.accessToken(), TASKS + "/" + inSection).andExpect(status().isOk());
        authPatch(user.accessToken(), TASKS + "/" + inSection, "{\"version\": 0, \"content\": \"x\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Section đã lưu trữ"))
                .andExpect(jsonPath("$.code").value("section-archived"));

        testData.archiveProject(project);
        authGet(user.accessToken(), TASKS + "/" + task).andExpect(status().isOk());
        authPatch(user.accessToken(), TASKS + "/" + task, "{\"version\": 0, \"content\": \"x\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
    }

    @Test
    void isOverdue_isFloating_datetimeEarlierToday_true_dateToday_false() throws Exception {
        clock.freezeAt(Instant.parse("2026-10-23T05:00:00Z")); // 12:00 ngày 23 ở Asia/Ho_Chi_Minh
        RegisteredUser user = registerUser(uniqueEmail());

        authPost(user.accessToken(), TASKS, """
                {"content": "Họp sáng", "dueDatetime": "2026-10-23T11:00:00"}
                """).andExpect(jsonPath("$.isOverdue").value(true));
        authPost(user.accessToken(), TASKS, """
                {"content": "Họp chiều", "dueDatetime": "2026-10-23T13:00:00"}
                """).andExpect(jsonPath("$.isOverdue").value(false));
        authPost(user.accessToken(), TASKS, """
                {"content": "Trong ngày", "dueDate": "2026-10-23"}
                """).andExpect(jsonPath("$.isOverdue").value(false));
        authPost(user.accessToken(), TASKS, """
                {"content": "Có deadline đã qua", "deadlineDate": "2026-10-01"}
                """).andExpect(jsonPath("$.isOverdue").value(false)); // deadline không làm quá hạn (BR-04)
    }

    @Test
    void otherUser_gets404Everywhere() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID id = createTask(alice, "{\"content\": \"Riêng\"}");
        String path = TASKS + "/" + id;

        authGet(bob.accessToken(), path).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Không tìm thấy công việc"));
        authPatch(bob.accessToken(), path, "{\"version\": 0, \"content\": \"x\"}").andExpect(status().isNotFound());
        authDelete(bob.accessToken(), path).andExpect(status().isNotFound());
        authPost(bob.accessToken(), TASKS, "{\"parentId\": \"%s\", \"content\": \"x\"}".formatted(id))
                .andExpect(status().isNotFound());
        authGet(alice.accessToken(), path).andExpect(status().isOk());
    }
}
