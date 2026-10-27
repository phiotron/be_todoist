package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import com.taskly.support.TestData.TaskRow;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Cây mẫu (spec 5.4):  r ─┬─ a ─── a1
 *                       └─ b (đã xong lúc DONE_EARLIER, không có completion_batch_id)
 * Mỗi lần gọi close sinh MỘT completion_batch_id mới (spec 5.1); mọi task đổi (kể cả task lan truyền) tăng version.
 */
class TaskCompletionApiTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
    private static final Instant DONE_EARLIER = Instant.parse("2026-10-20T03:00:00Z");

    private RegisteredUser user;
    private UUID project;
    private UUID r;
    private UUID a;
    private UUID a1;
    private UUID b;

    @BeforeEach
    void tree() throws Exception {
        clock.freezeAt(NOW);
        user = registerUser(uniqueEmail());
        project = testData.project(user.id(), "P");
        r = testData.task(user.id(), project, "r");
        a = testData.insert(TaskRow.of(user.id(), project, "a").parent(r));
        a1 = testData.insert(TaskRow.of(user.id(), project, "a1").parent(a));
        b = testData.insert(TaskRow.of(user.id(), project, "b").parent(r).completedAt(DONE_EARLIER));
    }

    private Instant completedAt(UUID id) {
        Timestamp value = jdbcTemplate.queryForObject("select completed_at from tasks where id = ?", Timestamp.class, id);
        return value == null ? null : value.toInstant();
    }

    private String close(UUID id) {
        return "/api/v1/tasks/" + id + "/close";
    }

    private String reopen(UUID id) {
        return "/api/v1/tasks/" + id + "/reopen";
    }

    private String cascaded(UUID id, String field) {
        return "$.cascaded[?(@.id == '%s')].%s".formatted(id, field);
    }

    @Test
    void closeParent_completesOpenDescendants_withSameTimeAndBatch_bumpsVersions_keepsEarlierCompletion() throws Exception {
        authPost(user.accessToken(), close(r))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.id").value(r.toString()))
                .andExpect(jsonPath("$.task.completedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.task.version").value(1))
                .andExpect(jsonPath("$.cascadedTaskIds").doesNotExist()) // thay hẳn bằng `cascaded` (spec 12.3)
                .andExpect(jsonPath("$.cascaded[*].id", containsInAnyOrder(a.toString(), a1.toString())))
                .andExpect(jsonPath(cascaded(a, "version")).value(contains(1))) // `cascaded` KHÔNG gồm chính r
                .andExpect(jsonPath(cascaded(a1, "version")).value(contains(1)))
                .andExpect(jsonPath(cascaded(a1, "completedAt")).value(contains(NOW.toString())));

        assertThat(completedAt(a)).isEqualTo(NOW);
        assertThat(completedAt(a1)).isEqualTo(NOW);
        assertThat(completedAt(b)).isEqualTo(DONE_EARLIER);
        UUID batch = testData.taskCompletionBatch(r);
        assertThat(batch).isNotNull();
        assertThat(testData.taskCompletionBatch(a)).isEqualTo(batch);
        assertThat(testData.taskCompletionBatch(a1)).isEqualTo(batch);
        assertThat(testData.taskCompletionBatch(b)).as("đã xong từ trước: giữ nguyên").isNull();
        assertThat(testData.taskVersion(r)).isEqualTo(1);
        assertThat(testData.taskVersion(a)).isEqualTo(1);
        assertThat(testData.taskVersion(a1)).isEqualTo(1);
        assertThat(testData.taskVersion(b)).isZero();
    }

    @Test
    void closeTwice_returnsCurrentStateWithoutOverwriting_batchUnchanged() throws Exception {
        authPost(user.accessToken(), close(r)).andExpect(status().isOk());
        UUID batch = testData.taskCompletionBatch(r);
        clock.advance(Duration.ofMinutes(10));

        authPost(user.accessToken(), close(r))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.completedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.task.version").value(1))
                .andExpect(jsonPath("$.cascaded", empty()));
        assertThat(testData.taskCompletionBatch(r)).isEqualTo(batch);
        assertThat(testData.taskCompletionBatch(a)).isEqualTo(batch);
    }

    @Test
    void reopenChild_reopensCompletedAncestors_butNotSiblings_andClearsBatch() throws Exception {
        authPost(user.accessToken(), close(r)).andExpect(status().isOk());

        authPost(user.accessToken(), reopen(a1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.task.version").value(2))
                .andExpect(jsonPath("$.cascaded[*].id", containsInAnyOrder(a.toString(), r.toString())))
                .andExpect(jsonPath("$.cascaded[0].completedAt").value(nullValue())) // null = đã mở lại
                .andExpect(jsonPath("$.cascaded[1].completedAt").value(nullValue()))
                .andExpect(jsonPath(cascaded(a, "version")).value(contains(2)));

        assertThat(completedAt(r)).isNull();
        assertThat(completedAt(a)).isNull();
        assertThat(completedAt(b)).isEqualTo(DONE_EARLIER);
        assertThat(testData.taskCompletionBatch(a1)).isNull(); // reopen xoá completion_batch_id
        assertThat(testData.taskCompletionBatch(a)).isNull();
        assertThat(testData.taskCompletionBatch(r)).isNull();
    }

    @Test
    void reopenParentWithoutUndo_reopensOnlyTheParent_childrenKeepTheirBatch() throws Exception {
        authPost(user.accessToken(), close(r)).andExpect(status().isOk());
        UUID batch = testData.taskCompletionBatch(a);

        authPost(user.accessToken(), reopen(r))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cascaded", empty()));

        assertThat(completedAt(r)).isNull();
        assertThat(completedAt(a)).isEqualTo(NOW);
        assertThat(completedAt(a1)).isEqualTo(NOW);
        assertThat(testData.taskCompletionBatch(r)).isNull();
        assertThat(testData.taskCompletionBatch(a)).isEqualTo(batch);
    }

    @Test
    void undo_reopensParentAndOnlyChildrenOfTheSameBatch() throws Exception {
        authPost(user.accessToken(), close(r)).andExpect(status().isOk());
        clock.advance(Duration.ofSeconds(3)); // trong 5 giây của snackbar

        authPost(user.accessToken(), reopen(r), "{\"undo\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.completedAt").value(nullValue()))
                .andExpect(jsonPath("$.cascaded[*].id", containsInAnyOrder(a.toString(), a1.toString())))
                .andExpect(jsonPath(cascaded(a1, "version")).value(contains(2)));

        assertThat(completedAt(a)).isNull();
        assertThat(completedAt(a1)).isNull();
        assertThat(completedAt(b)).as("con đã xong từ trước giữ nguyên").isEqualTo(DONE_EARLIER);
        assertThat(testData.taskCompletionBatch(a)).isNull();
        assertThat(testData.taskCompletionBatch(a1)).isNull();
    }

    @Test
    void undoChild_reopensItsCompletedAncestors_butNotSiblingsOfTheSameBatch() throws Exception {
        UUID a2 = testData.insert(TaskRow.of(user.id(), project, "a2").parent(a));
        authPost(user.accessToken(), close(r)).andExpect(status().isOk()); // r, a, a1, a2 cùng một batch

        authPost(user.accessToken(), reopen(a1), "{\"undo\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cascaded[*].id", containsInAnyOrder(a.toString(), r.toString())));

        assertThat(completedAt(a1)).isNull();
        assertThat(completedAt(a)).isNull();
        assertThat(completedAt(r)).isNull();
        assertThat(completedAt(a2)).as("anh em cùng batch không phải con cháu của a1: giữ nguyên").isEqualTo(NOW);
    }

    @Test
    void twoConsecutiveCloses_getDifferentBatchIds_andUndoOnlyTouchesItsOwnBatch() throws Exception {
        authPost(user.accessToken(), close(a1)).andExpect(status().isOk());
        UUID first = testData.taskCompletionBatch(a1);
        clock.advance(Duration.ofSeconds(1));
        authPost(user.accessToken(), close(a)).andExpect(status().isOk()) // a1 đã xong từ lần trước → chỉ a
                .andExpect(jsonPath("$.cascaded", empty()));
        UUID second = testData.taskCompletionBatch(a);
        clock.advance(Duration.ofSeconds(1));
        authPost(user.accessToken(), close(r)).andExpect(status().isOk()) // a, a1, b đã xong → chỉ r
                .andExpect(jsonPath("$.cascaded", empty()));
        UUID third = testData.taskCompletionBatch(r);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull().isNotEqualTo(first);
        assertThat(third).isNotNull().isNotEqualTo(first).isNotEqualTo(second);

        authPost(user.accessToken(), reopen(r), "{\"undo\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cascaded", empty())); // a, a1 thuộc batch khác → không bị mở lại

        assertThat(completedAt(r)).isNull();
        assertThat(testData.taskCompletionBatch(a)).isEqualTo(second);
        assertThat(testData.taskCompletionBatch(a1)).isEqualTo(first);
        assertThat(completedAt(a)).isNotNull();
        assertThat(completedAt(a1)).isNotNull();
        assertThat(completedAt(b)).isEqualTo(DONE_EARLIER);
        assertThat(testData.taskCompletionBatch(b)).isNull();
    }

    @Test
    void undoOfTaskWithoutBatch_reopensOnlyTheTaskAndItsAncestors() throws Exception {
        jdbcTemplate.update("update tasks set completed_at = ? where id in (?, ?)", Timestamp.from(DONE_EARLIER), r, a);

        authPost(user.accessToken(), reopen(r), "{\"undo\": true}") // r không có completion_batch_id
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cascaded", empty()));

        assertThat(completedAt(r)).isNull();
        assertThat(completedAt(a)).as("batch null: không kéo con cháu").isEqualTo(DONE_EARLIER);
    }

    @Test
    void reopenOrUndoOpenTask_isNoop200() throws Exception {
        authPost(user.accessToken(), reopen(a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.version").value(0))
                .andExpect(jsonPath("$.cascaded", empty()));
        authPost(user.accessToken(), reopen(a), "{\"undo\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cascaded", empty()));
    }

    @Test
    void reopen_overOpenTaskLimit_is422() throws Exception {
        authPost(user.accessToken(), close(r)).andExpect(status().isOk()); // 0 task đang mở trong dự án
        usageLimits.overrideMax(LimitKind.PROJECT_ACTIVE_TASKS, 2);

        authPost(user.accessToken(), reopen(r), "{\"undo\": true}") // cần mở lại 3
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.limit").value("PROJECT_ACTIVE_TASKS"));
        assertThat(completedAt(r)).isEqualTo(NOW);
        assertThat(testData.taskCompletionBatch(r)).isNotNull(); // rollback: batch còn nguyên
    }

    @Test
    void archivedProjectOrSection409_otherUser404_deleted404() throws Exception {
        RegisteredUser bob = registerUser(uniqueEmail());
        authPost(bob.accessToken(), close(r)).andExpect(status().isNotFound());
        authPost(bob.accessToken(), reopen(r)).andExpect(status().isNotFound());

        UUID section = testData.section(project, "S");
        UUID inSection = testData.insert(TaskRow.of(user.id(), project, "Trong section").section(section));
        testData.archiveSection(section);
        authPost(user.accessToken(), close(inSection))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));

        testData.deleteTask(a1);
        authPost(user.accessToken(), close(a1)).andExpect(status().isNotFound());

        testData.archiveProject(project);
        authPost(user.accessToken(), close(r))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
        authPost(user.accessToken(), reopen(b)).andExpect(status().isConflict());
    }
}
