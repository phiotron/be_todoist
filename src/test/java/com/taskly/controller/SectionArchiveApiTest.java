package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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

/** Spec 5.7: lưu trữ section → task đang mở (kèm cây con) thành đã xong cùng lúc; khôi phục → task vẫn đã xong. */
class SectionArchiveApiTest extends IntegrationTestBase {

    private static final Instant NOW = Instant.parse("2026-10-23T03:00:00Z");
    private static final Instant DONE_EARLIER = Instant.parse("2026-10-20T03:00:00Z");

    private RegisteredUser user;
    private UUID project;
    private UUID section;
    private UUID other;
    private UUID root;
    private UUID child;
    private UUID doneEarlier;
    private UUID otherTask;

    @BeforeEach
    void data() throws Exception {
        clock.freezeAt(NOW);
        user = registerUser(uniqueEmail());
        project = testData.project(user.id(), "P");
        section = testData.section(project, "Lưu trữ");
        other = testData.section(project, "Khác");
        root = testData.insert(TaskRow.of(user.id(), project, "Gốc").section(section));
        child = testData.insert(TaskRow.of(user.id(), project, "Con").section(section).parent(root));
        doneEarlier = testData.insert(TaskRow.of(user.id(), project, "Xong trước").section(section)
                .completedAt(DONE_EARLIER));
        otherTask = testData.insert(TaskRow.of(user.id(), project, "Ở section khác").section(other));
    }

    private String path(UUID sectionId, String action) {
        return "/api/v1/projects/" + project + "/sections/" + sectionId + "/" + action;
    }

    private Instant completedAt(UUID id) {
        Timestamp value = jdbcTemplate.queryForObject("select completed_at from tasks where id = ?", Timestamp.class, id);
        return value == null ? null : value.toInstant();
    }

    @Test
    void archive_completesOpenTasksWithSubtrees_atSameTime_andSectionGoesLast() throws Exception {
        authPost(user.accessToken(), path(section, "archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.activeTaskCount").value(0));

        assertThat(completedAt(root)).isEqualTo(NOW);
        assertThat(completedAt(child)).isEqualTo(NOW);
        assertThat(completedAt(doneEarlier)).isEqualTo(DONE_EARLIER);
        assertThat(completedAt(otherTask)).isNull();
        authGet(user.accessToken(), "/api/v1/projects/" + project + "/sections")
                .andExpect(jsonPath("$[*].name", contains("Khác", "Lưu trữ")));

        clock.advance(Duration.ofMinutes(1));
        authPost(user.accessToken(), path(section, "archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value(NOW.toString())); // lặp lại không ghi đè
    }

    @Test
    void archive_usesOneBatchIdPerArchive_andBumpsVersionOfCompletedTasksOnly() throws Exception {
        authPost(user.accessToken(), path(section, "archive")).andExpect(status().isOk());

        UUID batch = testData.taskCompletionBatch(root);
        assertThat(batch).as("mọi task của MỘT lần lưu trữ chung một completion_batch_id").isNotNull();
        assertThat(testData.taskCompletionBatch(child)).isEqualTo(batch);
        assertThat(testData.taskCompletionBatch(doneEarlier)).as("đã xong từ trước: giữ nguyên").isNull();
        assertThat(testData.taskCompletionBatch(otherTask)).isNull();
        assertThat(testData.taskVersion(root)).isEqualTo(1); // task chuyển sang hoàn thành → version + 1
        assertThat(testData.taskVersion(child)).isEqualTo(1);
        assertThat(testData.taskVersion(doneEarlier)).isZero();
        assertThat(testData.taskVersion(otherTask)).isZero();

        clock.advance(Duration.ofMinutes(1));
        authPost(user.accessToken(), path(other, "archive")).andExpect(status().isOk());
        UUID otherBatch = testData.taskCompletionBatch(otherTask);
        assertThat(otherBatch).isNotNull().isNotEqualTo(batch); // lần lưu trữ khác → batch khác

        authPost(user.accessToken(), path(section, "archive")).andExpect(status().isOk()); // lặp lại
        assertThat(testData.taskCompletionBatch(root)).isEqualTo(batch);
        assertThat(testData.taskVersion(root)).isEqualTo(1);
    }

    @Test
    void archivedSection_tasksAreReadOnly_includingDelete_andCannotReceiveNewTasks() throws Exception {
        authPost(user.accessToken(), path(section, "archive")).andExpect(status().isOk());

        authPatch(user.accessToken(), "/api/v1/tasks/" + root, "{\"version\": 1, \"content\": \"x\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));
        authPost(user.accessToken(), "/api/v1/tasks/" + root + "/reopen")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));
        authPost(user.accessToken(), "/api/v1/tasks", """
                {"projectId": "%s", "sectionId": "%s", "content": "Mới"}
                """.formatted(project, section))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("target-archived"));
        // Bản 3.1 bỏ ngoại lệ: xoá task của section lưu trữ cũng → 409 section-archived (spec 5.7, 12.10)
        authDelete(user.accessToken(), "/api/v1/tasks/" + child)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where id = ?", Integer.class, child)).isOne();
    }

    @Test
    void archivedSection_itselfCanBeDeleted_takingItsTasksAlong() throws Exception {
        authPost(user.accessToken(), path(section, "archive")).andExpect(status().isOk());

        authDelete(user.accessToken(), "/api/v1/projects/" + project + "/sections/" + section)
                .andExpect(status().isNoContent()); // được xoá section lưu trữ (xoá luôn task của nó)

        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where id in (?, ?, ?)", Integer.class,
                root, child, doneEarlier)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where id = ?", Integer.class, otherTask))
                .isOne();
    }

    @Test
    void unarchive_makesSectionActiveAgain_butTasksStayCompleted() throws Exception {
        authPost(user.accessToken(), path(section, "archive")).andExpect(status().isOk());
        clock.advance(Duration.ofMinutes(5));

        authPost(user.accessToken(), path(section, "unarchive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value(nullValue()));
        assertThat(completedAt(root)).isEqualTo(NOW);
        assertThat(completedAt(child)).isEqualTo(NOW);
        authPost(user.accessToken(), "/api/v1/tasks/" + root + "/reopen").andExpect(status().isOk()); // ghi được lại
        authPost(user.accessToken(), path(section, "unarchive")).andExpect(status().isOk()); // lặp lại vẫn 200
    }

    @Test
    void unarchive_overActiveSectionLimit_is422() throws Exception {
        authPost(user.accessToken(), path(section, "archive")).andExpect(status().isOk());
        usageLimits.overrideMax(LimitKind.PROJECT_SECTIONS, 1); // còn "Khác" đang hoạt động

        authPost(user.accessToken(), path(section, "unarchive"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.limit").value("PROJECT_SECTIONS"));
    }

    @Test
    void unarchive_isNotBlockedByTotalSectionLimit_becauseTheTotalDoesNotGrow() throws Exception {
        authPost(user.accessToken(), path(section, "archive")).andExpect(status().isOk());
        usageLimits.overrideMax(LimitKind.PROJECT_SECTIONS_TOTAL, 2); // đang có đúng 2 section (kể cả lưu trữ)

        authPost(user.accessToken(), path(section, "unarchive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").value(nullValue()));
    }

    @Test
    void archivedProject409_otherUser404_unknownSection404() throws Exception {
        RegisteredUser bob = registerUser(uniqueEmail());
        authPost(bob.accessToken(), path(section, "archive")).andExpect(status().isNotFound());
        authPost(user.accessToken(), path(UUID.randomUUID(), "archive")).andExpect(status().isNotFound());

        testData.archiveProject(project);
        authPost(user.accessToken(), path(section, "archive"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
        assertThat(completedAt(root)).isNull();
        // xoá section trong dự án lưu trữ → 409 (chỉ xoá cả dự án được); xoá task cũng 409
        authDelete(user.accessToken(), "/api/v1/projects/" + project + "/sections/" + other)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
        authDelete(user.accessToken(), "/api/v1/tasks/" + otherTask)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
    }
}
