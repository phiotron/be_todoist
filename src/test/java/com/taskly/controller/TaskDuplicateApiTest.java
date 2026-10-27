package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import com.taskly.support.TestData.TaskRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * Cây mẫu:  r(sort 1024, P2, 09:00 ngày 25, 45 phút, deadline 31/10) ─┬─ a (đã xong) ── a1 (đang mở)
 *                                                                  └─ b ── b1
 *           n (sort 2048, anh em sau r)
 * Spec 5.4: sao chép chỉ con cháu ĐANG MỞ — a không chép, a1 (dưới a đã xong) cũng không.
 */
class TaskDuplicateApiTest extends IntegrationTestBase {

    private static final Instant DONE = Instant.parse("2026-10-20T03:00:00Z");

    private RegisteredUser user;
    private UUID project;
    private UUID r;
    private UUID b;

    @BeforeEach
    void tree() throws Exception {
        user = registerUser(uniqueEmail());
        project = testData.project(user.id(), "P");
        r = testData.insert(TaskRow.of(user.id(), project, "Gốc").sortOrder(1024).priority(2)
                .dueDatetime(LocalDateTime.of(2026, 10, 25, 9, 0)).durationMinutes(45)
                .deadlineDate(LocalDate.of(2026, 10, 31)));
        UUID a = testData.insert(TaskRow.of(user.id(), project, "a").parent(r).sortOrder(1024).completedAt(DONE));
        testData.insert(TaskRow.of(user.id(), project, "a1").parent(a).sortOrder(1024));
        b = testData.insert(TaskRow.of(user.id(), project, "b").parent(r).sortOrder(2048));
        testData.insert(TaskRow.of(user.id(), project, "b1").parent(b).sortOrder(1024));
        testData.insert(TaskRow.of(user.id(), project, "n").sortOrder(2048));
    }

    private String duplicate(UUID id) {
        return "/api/v1/tasks/" + id + "/duplicate";
    }

    private List<Map<String, Object>> childrenOf(UUID parentId) {
        return jdbcTemplate.queryForList("select id, content, completed_at, sort_order from tasks "
                + "where parent_id = ? order by sort_order", parentId);
    }

    @Test
    void duplicateRoot_copiesFieldsAndOnlyOpenDescendants_rightAfterOriginal() throws Exception {
        UUID copy = readUuid(authPost(user.accessToken(), duplicate(r))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.content").value("Gốc"))
                .andExpect(jsonPath("$.priority").value(2))
                .andExpect(jsonPath("$.dueDatetime").value("2026-10-25T09:00:00"))
                .andExpect(jsonPath("$.durationMinutes").value(45))
                .andExpect(jsonPath("$.deadlineDate").value("2026-10-31"))
                .andExpect(jsonPath("$.parentId").value(nullValue()))
                .andExpect(jsonPath("$.sortOrder").value(1536))
                .andExpect(jsonPath("$.subtaskCount").value(1))
                .andExpect(jsonPath("$.completedSubtaskCount").value(0))
                .andExpect(jsonPath("$.version").value(0)), "$.id");
        assertThat(copy).isNotEqualTo(r);
        assertThat(testData.taskVersion(copy)).isZero(); // bản sao mới: version 0
        assertThat(testData.taskWorkspace(copy)).isEqualTo(testData.workspaceOf(user.id())); // có workspace_id

        List<Map<String, Object>> children = childrenOf(copy);
        assertThat(children).extracting(row -> row.get("content")).containsExactly("b");
        assertThat(children.get(0).get("completed_at")).isNull();
        UUID copyOfB = (UUID) children.get(0).get("id");
        assertThat(testData.taskWorkspace(copyOfB)).isEqualTo(testData.workspaceOf(user.id()));
        assertThat(testData.taskVersion(copyOfB)).isZero();
        assertThat(childrenOf(copyOfB)).extracting(row -> row.get("content")).containsExactly("b1");
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where project_id = ? and content = 'a1'",
                Integer.class, project)).isOne(); // a1 không bị chép
        assertThat(testData.taskVersion(r)).as("bản gốc không bị đụng").isZero();
    }

    @Test
    void duplicateCompletedRoot_copyIsAlwaysOpen_andHasNoCompletionBatch() throws Exception {
        UUID batch = UUID.randomUUID();
        jdbcTemplate.update("update tasks set completed_at = now(), completion_batch_id = ? where id = ?", batch, r);

        UUID copy = readUuid(authPost(user.accessToken(), duplicate(r))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.completedAt").value(nullValue())), "$.id");

        assertThat(testData.taskCompletionBatch(copy)).isNull(); // bản sao không thừa hưởng batch Undo
        assertThat(testData.taskCompletionBatch(r)).isEqualTo(batch);
    }

    @Test
    void duplicate_withNoGapRightAfterOriginal_renumbersSiblingsNatively_withoutBumpingVersions() throws Exception {
        jdbcTemplate.update("update tasks set sort_order = 1025 where project_id = ? and content = 'n'", project);
        UUID n = jdbcTemplate.queryForObject("select id from tasks where project_id = ? and content = 'n'",
                UUID.class, project);

        UUID copy = readUuid(authPost(user.accessToken(), duplicate(r)).andExpect(status().isCreated()), "$.id");

        assertThat(jdbcTemplate.queryForList("select content from tasks where project_id = ? and parent_id is null "
                + "order by sort_order", String.class, project)).containsExactly("Gốc", "Gốc", "n");
        assertThat(testData.taskSortOrder(r)).isLessThan(testData.taskSortOrder(copy));
        assertThat(testData.taskSortOrder(copy)).isLessThan(testData.taskSortOrder(n));
        assertThat(testData.taskVersion(r)).isZero(); // đánh số lại = native UPDATE, không tăng version
        assertThat(testData.taskVersion(n)).isZero();
    }

    @Test
    void duplicate_withIdempotencyKey_createsOnlyOneCopy() throws Exception {
        String key = UUID.randomUUID().toString();

        String first = readString(mockMvc.perform(post(duplicate(r))
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())).header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replay")), "$.id");
        mockMvc.perform(post(duplicate(r))
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())).header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(first));

        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where project_id = ? and content = 'Gốc'",
                Integer.class, project)).isEqualTo(2); // bản gốc + đúng một bản sao
    }

    @Test
    void duplicateSubtask_staysUnderSameParent_rightAfterOriginal() throws Exception {
        authPost(user.accessToken(), duplicate(b))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentId").value(r.toString()))
                .andExpect(jsonPath("$.sortOrder").value(3072))
                .andExpect(jsonPath("$.subtaskCount").value(1));
    }

    @Test
    void duplicate_respectsOpenTaskLimit() throws Exception {
        // Đang mở: r, a1, b, b1, n = 5; bản sao của r gồm r, b, b1 = 3.
        usageLimits.overrideMax(LimitKind.PROJECT_ACTIVE_TASKS, 7);
        authPost(user.accessToken(), duplicate(r))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.limit").value("PROJECT_ACTIVE_TASKS"));
        usageLimits.overrideMax(LimitKind.PROJECT_ACTIVE_TASKS, 8);
        authPost(user.accessToken(), duplicate(r)).andExpect(status().isCreated());
    }

    @Test
    void archivedProjectOrSection409_otherUser404() throws Exception {
        RegisteredUser bob = registerUser(uniqueEmail());
        authPost(bob.accessToken(), duplicate(r)).andExpect(status().isNotFound());

        UUID section = testData.section(project, "S");
        UUID inSection = testData.insert(TaskRow.of(user.id(), project, "Trong section").section(section));
        testData.archiveSection(section);
        authPost(user.accessToken(), duplicate(inSection))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));

        testData.archiveProject(project);
        authPost(user.accessToken(), duplicate(r))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
    }
}
