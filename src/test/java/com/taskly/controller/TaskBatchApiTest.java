package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import com.taskly.support.TestData.TaskRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Cây mẫu:  r ─── a ─── a1 ;  b ;  c (09:15 ngày 20, 30 phút) ;  d (ngày 20)  — đều ở dự án P.
 * Giờ đóng băng 2026-10-23T03:00:00Z (10:00 ngày 23 giờ Việt Nam).
 */
class TaskBatchApiTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
    private static final String BATCH = "/api/v1/tasks/batch";

    private RegisteredUser user;
    private UUID project;
    private UUID target;
    private UUID r;
    private UUID a;
    private UUID a1;
    private UUID b;
    private UUID c;
    private UUID d;

    @BeforeEach
    void data() throws Exception {
        clock.freezeAt(NOW);
        user = registerUser(uniqueEmail());
        project = testData.project(user.id(), "P");
        target = testData.project(user.id(), "Đích");
        r = testData.insert(TaskRow.of(user.id(), project, "r").sortOrder(1024));
        a = testData.insert(TaskRow.of(user.id(), project, "a").parent(r));
        a1 = testData.insert(TaskRow.of(user.id(), project, "a1").parent(a));
        b = testData.insert(TaskRow.of(user.id(), project, "b").sortOrder(2048));
        c = testData.insert(TaskRow.of(user.id(), project, "c").sortOrder(3072)
                .dueDatetime(LocalDateTime.of(2026, 10, 20, 9, 15)).durationMinutes(30));
        d = testData.insert(TaskRow.of(user.id(), project, "d").sortOrder(4096).dueDate(LocalDate.of(2026, 10, 20)));
    }

    private ResultActions batch(String json) throws Exception {
        return authPost(user.accessToken(), BATCH, json);
    }

    private static String ids(UUID... ids) {
        return java.util.Arrays.stream(ids).map(id -> "\"" + id + "\"").collect(Collectors.joining(", ", "[", "]"));
    }

    private Instant completedAt(UUID id) {
        Timestamp value = jdbcTemplate.queryForObject("select completed_at from tasks where id = ?", Timestamp.class, id);
        return value == null ? null : value.toInstant();
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    @Test
    void close_completesEachWithItsOpenSubtreeAtSameTime_andAffectedIdsAreDeduplicated() throws Exception {
        batch("{\"action\": \"CLOSE\", \"taskIds\": %s}".formatted(ids(a, r, b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskIds",
                        containsInAnyOrder(r.toString(), a.toString(), a1.toString(), b.toString())));

        for (UUID id : List.of(r, a, a1, b)) {
            assertThat(completedAt(id)).as(id.toString()).isEqualTo(NOW);
        }
        // MỘT completion_batch_id chung cho mọi task hoàn thành trong request (spec 5.1); mỗi task đổi tăng version
        UUID batch = testData.taskCompletionBatch(r);
        assertThat(batch).isNotNull();
        for (UUID id : List.of(a, a1, b)) {
            assertThat(testData.taskCompletionBatch(id)).as(id.toString()).isEqualTo(batch);
            assertThat(testData.taskVersion(id)).as(id.toString()).isEqualTo(1);
        }
        assertThat(testData.taskVersion(r)).isEqualTo(1);
        assertThat(testData.taskVersion(c)).isZero();
        assertThat(completedAt(c)).isNull();
        batch("{\"action\": \"CLOSE\", \"taskIds\": %s}".formatted(ids(b)))      // lặp lại: idempotent
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskIds").isEmpty());
    }

    @Test
    void reopenWithUndo_reopensParentAndTheChildrenCompletedInTheSameBatch_only() throws Exception {
        jdbcTemplate.update("update tasks set completed_at = ? where id = ?", Timestamp.from(Instant.parse("2026-10-01T03:00:00Z")), a1);
        batch("{\"action\": \"CLOSE\", \"taskIds\": %s}".formatted(ids(r, b))).andExpect(status().isOk());

        batch("{\"action\": \"REOPEN\", \"undo\": true, \"taskIds\": %s}".formatted(ids(r, b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskIds", containsInAnyOrder(r.toString(), a.toString(), b.toString())));

        assertThat(completedAt(r)).isNull();
        assertThat(completedAt(a)).isNull();
        assertThat(completedAt(b)).isNull();
        assertThat(completedAt(a1)).as("con đã xong từ trước giữ nguyên").isEqualTo(Instant.parse("2026-10-01T03:00:00Z"));
        assertThat(testData.taskCompletionBatch(r)).as("Undo xoá completion_batch_id").isNull();
        assertThat(testData.taskCompletionBatch(a)).isNull();
    }

    @Test
    void reopenWithoutUndo_reopensOnlyTheListedTasksAndTheirCompletedAncestors() throws Exception {
        batch("{\"action\": \"CLOSE\", \"taskIds\": %s}".formatted(ids(r))).andExpect(status().isOk());

        batch("{\"action\": \"REOPEN\", \"taskIds\": %s}".formatted(ids(a1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskIds", containsInAnyOrder(a1.toString(), a.toString(), r.toString())));
        assertThat(completedAt(r)).isNull();
    }

    @Test
    void move_dropsDescendantsWhoseAncestorIsListed_appendsRootsInOrder_toTarget() throws Exception {
        UUID section = testData.section(target, "S đích");
        testData.insert(TaskRow.of(user.id(), target, "Có sẵn").section(section).sortOrder(5000));

        batch("{\"action\": \"MOVE\", \"projectId\": \"%s\", \"sectionId\": \"%s\", \"taskIds\": %s}"
                .formatted(target, section, ids(a, r, b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskIds",
                        containsInAnyOrder(r.toString(), a.toString(), a1.toString(), b.toString())));

        List<String> order = jdbcTemplate.queryForList("select content from tasks where project_id = ? "
                + "and section_id = ? and parent_id is null order by sort_order", String.class, target, section);
        assertThat(order).containsExactly("Có sẵn", "r", "b"); // thứ tự = thứ tự trong taskIds sau khi bỏ a
        assertThat(count("select count(*) from tasks where project_id = ?", target)).isEqualTo(5); // r, a, a1, b, Có sẵn
        authGet(user.accessToken(), "/api/v1/tasks/" + a1).andExpect(jsonPath("$.projectId").value(target.toString()))
                .andExpect(jsonPath("$.sectionId").value(section.toString()));
    }

    @Test
    void move_subtaskBecomesRootAtTarget_andNullSectionMeansNoSection() throws Exception {
        batch("{\"action\": \"MOVE\", \"projectId\": \"%s\", \"taskIds\": %s}".formatted(target, ids(a)))
                .andExpect(status().isOk());

        authGet(user.accessToken(), "/api/v1/tasks/" + a)
                .andExpect(jsonPath("$.parentId").value(nullValue()))
                .andExpect(jsonPath("$.sectionId").value(nullValue()))
                .andExpect(jsonPath("$.projectId").value(target.toString()));
        authGet(user.accessToken(), "/api/v1/tasks/" + a1).andExpect(jsonPath("$.projectId").value(target.toString()));
    }

    @Test
    void delete_removesRootsWithWholeSubtrees_permanently() throws Exception {
        batch("{\"action\": \"DELETE\", \"taskIds\": %s}".formatted(ids(a1, r)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskIds", containsInAnyOrder(r.toString(), a.toString(), a1.toString())));

        assertThat(count("select count(*) from tasks where id in (?, ?, ?)", r, a, a1)).isZero();
        assertThat(count("select count(*) from tasks where id in (?, ?, ?)", b, c, d)).isEqualTo(3);
    }

    @Test
    void delete_isRejectedInAnArchivedSectionOrProject_409_andNothingIsDeleted() throws Exception {
        // Bản 3.1 BỎ ngoại lệ "xoá được trong dự án / section lưu trữ" (spec 5.7, 12.5, I15): kể cả DELETE → 409.
        UUID section = testData.section(project, "S");
        UUID inSection = testData.insert(TaskRow.of(user.id(), project, "Trong section").section(section));
        testData.archiveSection(section);
        batch("{\"action\": \"DELETE\", \"taskIds\": %s}".formatted(ids(b, inSection)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));
        assertThat(count("select count(*) from tasks where id in (?, ?)", b, inSection)).isEqualTo(2);

        UUID archivedProject = testData.project(user.id(), "Lưu trữ");
        UUID inArchivedProject = testData.task(user.id(), archivedProject, "Trong dự án lưu trữ");
        testData.archiveProject(archivedProject);
        batch("{\"action\": \"DELETE\", \"taskIds\": %s}".formatted(ids(b, inArchivedProject)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
        assertThat(count("select count(*) from tasks where id in (?, ?)", b, inArchivedProject)).isEqualTo(2);
    }

    @Test
    void scheduleAndMove_withACompletedTask_return409TaskCompleted_nothingChanges() throws Exception {
        batch("{\"action\": \"CLOSE\", \"taskIds\": %s}".formatted(ids(b))).andExpect(status().isOk());

        batch("{\"action\": \"SCHEDULE\", \"date\": \"2026-10-27\", \"taskIds\": %s}".formatted(ids(c, b)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("task-completed"));
        batch("{\"action\": \"MOVE\", \"projectId\": \"%s\", \"taskIds\": %s}".formatted(target, ids(c, b)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("task-completed"));
        authGet(user.accessToken(), "/api/v1/tasks/" + c)
                .andExpect(jsonPath("$.dueDatetime").value("2026-10-20T09:15:00"))
                .andExpect(jsonPath("$.projectId").value(project.toString()));
        batch("{\"action\": \"PRIORITY\", \"priority\": 1, \"taskIds\": %s}".formatted(ids(b)))
                .andExpect(status().isOk()); // chỉ SCHEDULE / MOVE bị chặn bởi task đã xong
    }

    @Test
    void reopen_acrossTwoProjects_locksBothAndReopensBoth() throws Exception {
        UUID inTarget = testData.insert(TaskRow.of(user.id(), target, "Ở đích").completedAt(clock.instant()));
        batch("{\"action\": \"CLOSE\", \"taskIds\": %s}".formatted(ids(b))).andExpect(status().isOk());

        batch("{\"action\": \"REOPEN\", \"taskIds\": %s}".formatted(ids(inTarget, b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskIds", containsInAnyOrder(inTarget.toString(), b.toString())));
        assertThat(completedAt(b)).isNull();
        assertThat(completedAt(inTarget)).isNull();
    }

    @Test
    void schedule_dateKeepsTimeAndDuration_nullClearsDueAndDuration() throws Exception {
        batch("{\"action\": \"SCHEDULE\", \"date\": \"2026-10-27\", \"taskIds\": %s}".formatted(ids(c, d, b)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskIds", containsInAnyOrder(c.toString(), d.toString(), b.toString())));

        authGet(user.accessToken(), "/api/v1/tasks/" + c)
                .andExpect(jsonPath("$.dueDatetime").value("2026-10-27T09:15:00"))
                .andExpect(jsonPath("$.durationMinutes").value(30));
        authGet(user.accessToken(), "/api/v1/tasks/" + d)
                .andExpect(jsonPath("$.dueDate").value("2026-10-27")).andExpect(jsonPath("$.dueDatetime").value(nullValue()));
        authGet(user.accessToken(), "/api/v1/tasks/" + b).andExpect(jsonPath("$.dueDate").value("2026-10-27"));

        batch("{\"action\": \"SCHEDULE\", \"date\": null, \"taskIds\": %s}".formatted(ids(c, d)))
                .andExpect(status().isOk());
        authGet(user.accessToken(), "/api/v1/tasks/" + c)
                .andExpect(jsonPath("$.dueDatetime").value(nullValue()))
                .andExpect(jsonPath("$.durationMinutes").value(nullValue()))
                .andExpect(jsonPath("$.version").value(2)); // batch vẫn tăng version
        authGet(user.accessToken(), "/api/v1/tasks/" + d).andExpect(jsonPath("$.dueDate").value(nullValue()));
    }

    @Test
    void priority_setsAllListedTasks() throws Exception {
        batch("{\"action\": \"PRIORITY\", \"priority\": 1, \"taskIds\": %s}".formatted(ids(b, c)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedTaskIds", containsInAnyOrder(b.toString(), c.toString())));
        authGet(user.accessToken(), "/api/v1/tasks/" + b).andExpect(jsonPath("$.priority").value(1));
        authGet(user.accessToken(), "/api/v1/tasks/" + c).andExpect(jsonPath("$.priority").value(1));
        authGet(user.accessToken(), "/api/v1/tasks/" + d).andExpect(jsonPath("$.priority").value(4));
    }

    @Test
    void allOrNothing_unknownId404_archivedSection409_archivedTarget422_limit422() throws Exception {
        batch("{\"action\": \"CLOSE\", \"taskIds\": %s}".formatted(ids(b, UUID.randomUUID())))
                .andExpect(status().isNotFound());
        assertThat(completedAt(b)).isNull();

        UUID section = testData.section(project, "Lưu trữ");
        UUID inArchived = testData.insert(TaskRow.of(user.id(), project, "Trong section lưu trữ").section(section));
        testData.archiveSection(section);
        batch("{\"action\": \"PRIORITY\", \"priority\": 2, \"taskIds\": %s}".formatted(ids(b, inArchived)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));
        authGet(user.accessToken(), "/api/v1/tasks/" + b).andExpect(jsonPath("$.priority").value(4));

        UUID archivedProject = testData.project(user.id(), "Lưu trữ");
        testData.archiveProject(archivedProject);
        batch("{\"action\": \"MOVE\", \"projectId\": \"%s\", \"taskIds\": %s}".formatted(archivedProject, ids(b, c)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("target-archived"));

        usageLimits.overrideMax(LimitKind.PROJECT_ACTIVE_TASKS, 1);
        batch("{\"action\": \"MOVE\", \"projectId\": \"%s\", \"taskIds\": %s}".formatted(target, ids(b, c)))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.limit").value("PROJECT_ACTIVE_TASKS"));
        assertThat(count("select count(*) from tasks where project_id = ?", target)).isZero(); // b KHÔNG bị chuyển
    }

    @Test
    void invalidRequests_return400() throws Exception {
        String ok = ids(b);
        batch("{\"taskIds\": %s}".formatted(ok)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Thiếu action"));
        batch("{\"action\": \"CLOSE\", \"taskIds\": []}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("taskIds phải có từ 1 đến 200 id"));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            many.add("\"" + UUID.randomUUID() + "\"");
        }
        batch("{\"action\": \"CLOSE\", \"taskIds\": [%s]}".formatted(String.join(",", many)))
                .andExpect(status().isBadRequest());
        batch("{\"action\": \"CLOSE\", \"taskIds\": %s}".formatted(ids(b, b))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("taskIds không được trùng nhau"));
        batch("{\"action\": \"CLOSE\", \"undo\": true, \"taskIds\": %s}".formatted(ok)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Các trường gửi lên không khớp với action"));
        batch("{\"action\": \"CLOSE\", \"projectId\": \"%s\", \"taskIds\": %s}".formatted(target, ok))
                .andExpect(status().isBadRequest());
        batch("{\"action\": \"CLOSE\", \"date\": null, \"taskIds\": %s}".formatted(ok)).andExpect(status().isBadRequest());
        batch("{\"action\": \"CLOSE\", \"priority\": 1, \"taskIds\": %s}".formatted(ok)).andExpect(status().isBadRequest());
        batch("{\"action\": \"MOVE\", \"taskIds\": %s}".formatted(ok)).andExpect(status().isBadRequest());
        batch("{\"action\": \"SCHEDULE\", \"taskIds\": %s}".formatted(ok)).andExpect(status().isBadRequest()); // thiếu khoá date
        batch("{\"action\": \"PRIORITY\", \"taskIds\": %s}".formatted(ok)).andExpect(status().isBadRequest());
        batch("{\"action\": \"PRIORITY\", \"priority\": 5, \"taskIds\": %s}".formatted(ok)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Ưu tiên phải từ 1 đến 4"));
        batch("{\"action\": \"EXPLODE\", \"taskIds\": %s}".formatted(ok)).andExpect(status().isBadRequest());
        assertThat(completedAt(b)).isNull();
    }

    @Test
    void otherUser_gets404_andNothingChanges() throws Exception {
        RegisteredUser bob = registerUser(uniqueEmail());
        authPost(bob.accessToken(), BATCH, "{\"action\": \"CLOSE\", \"taskIds\": %s}".formatted(ids(b)))
                .andExpect(status().isNotFound());
        authPost(bob.accessToken(), BATCH, "{\"action\": \"DELETE\", \"taskIds\": %s}".formatted(ids(b, r)))
                .andExpect(status().isNotFound());
        assertThat(count("select count(*) from tasks where id in (?, ?) and completed_at is null", b, r)).isEqualTo(2);
    }
}
