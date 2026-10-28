package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import com.taskly.support.TestData.TaskRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * M11 F1 — nhân bản dự án: "Copy of …" / "Bản sao của …", ngay sau bản gốc, chỉ section đang dùng + task đang mở (cây
 * con đang mở), chép label; không chép yêu thích / dự án con / task xong / section lưu trữ.
 *
 * <pre>
 * P (mô tả "mô tả P", màu teal) ─ con: Child
 *   (không section) r ─┬─ a (xong) ── a1
 *                      └─ b ── b1
 *                   done (xong)
 *   S1: s1 (label L)
 *   S2 (lưu trữ): z (xong)
 * </pre>
 */
class ProjectDuplicateApiTest extends IntegrationTestBase {

    private static final Instant DONE = Instant.parse("2026-10-20T03:00:00Z");

    private RegisteredUser user;
    private UUID project;
    private UUID next;
    private UUID s1;
    private UUID label;

    @BeforeEach
    void data() throws Exception {
        user = registerUser(uniqueEmail());
        project = testData.project(user.id(), "Kế hoạch");
        next = testData.project(user.id(), "Sau");
        jdbcTemplate.update("update projects set color = 'teal', description = 'mô tả P' where id = ?", project);
        testData.childProject(user.id(), project, "Child");
        testData.favorite(user.id(), "PROJECT", project);
        UUID r = testData.insert(TaskRow.of(user.id(), project, "r").sortOrder(1024).priority(2)
                .dueDate(LocalDate.of(2026, 11, 2)));
        UUID a = testData.insert(TaskRow.of(user.id(), project, "a").parent(r).sortOrder(1024).completedAt(DONE));
        testData.insert(TaskRow.of(user.id(), project, "a1").parent(a).sortOrder(1024));
        UUID b = testData.insert(TaskRow.of(user.id(), project, "b").parent(r).sortOrder(2048));
        testData.insert(TaskRow.of(user.id(), project, "b1").parent(b).sortOrder(1024));
        testData.insert(TaskRow.of(user.id(), project, "done").sortOrder(2048).completedAt(DONE));
        s1 = testData.section(project, "S1");
        UUID t1 = testData.insert(TaskRow.of(user.id(), project, "s1").section(s1));
        UUID s2 = testData.section(project, "S2");
        testData.insert(TaskRow.of(user.id(), project, "z").section(s2).completedAt(DONE));
        testData.archiveSection(s2);
        label = UUID.randomUUID();
        jdbcTemplate.update("insert into labels (id, user_id, name, color, sort_order, created_at, updated_at) "
                + "values (?, ?, 'L', 'charcoal', 1024, now(), now())", label, user.id());
        jdbcTemplate.update("insert into task_labels (task_id, label_id) values (?, ?)", t1, label);
    }

    private String duplicate(UUID id) {
        return "/api/v1/projects/" + id + "/duplicate";
    }

    private List<String> contents(UUID projectId) {
        return jdbcTemplate.queryForList("select content from tasks where project_id = ? order by content",
                String.class, projectId);
    }

    @Test
    void duplicate_copiesActiveSectionsAndOpenTasks_rightAfterOriginal() throws Exception {
        UUID copy = readUuid(authPost(user.accessToken(), duplicate(project))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Bản sao của Kế hoạch"))
                .andExpect(jsonPath("$.color").value("teal"))
                .andExpect(jsonPath("$.description").value("mô tả P"))
                .andExpect(jsonPath("$.parentId").isEmpty())
                .andExpect(jsonPath("$.isFavorite").value(false))
                .andExpect(jsonPath("$.activeTaskCount").value(4))
                .andExpect(jsonPath("$.descendantProjectCount").value(0)), "$.id");

        assertThat(contents(copy)).containsExactly("b", "b1", "r", "s1");
        List<String> sections = jdbcTemplate.queryForList(
                "select name from sections where project_id = ? order by sort_order", String.class, copy);
        assertThat(sections).containsExactly("S1");

        // ngay sau bản gốc trong vùng gốc
        long original = jdbcTemplate.queryForObject("select sort_order from projects where id = ?", Long.class, project);
        long after = jdbcTemplate.queryForObject("select sort_order from projects where id = ?", Long.class, next);
        long copied = jdbcTemplate.queryForObject("select sort_order from projects where id = ?", Long.class, copy);
        assertThat(copied).isGreaterThan(original).isLessThan(after);

        // cây con giữ nguyên, task giữ ưu tiên + ngày, version 0, label được chép
        Map<String, Object> r = jdbcTemplate.queryForMap(
                "select id, priority, due_date, version, creator_id from tasks where project_id = ? and content = 'r'", copy);
        assertThat(r.get("priority")).isEqualTo(2);
        assertThat(r.get("due_date").toString()).isEqualTo("2026-11-02");
        assertThat(((Number) r.get("version")).longValue()).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where parent_id = ?", Integer.class,
                r.get("id"))).isOne();
        UUID copiedSection = jdbcTemplate.queryForObject(
                "select id from sections where project_id = ?", UUID.class, copy);
        assertThat(jdbcTemplate.queryForObject("select section_id from tasks where project_id = ? and content = 's1'",
                UUID.class, copy)).isEqualTo(copiedSection);
        assertThat(jdbcTemplate.queryForObject("select count(*) from task_labels tl join tasks t on t.id = tl.task_id "
                + "where t.project_id = ? and tl.label_id = ?", Integer.class, copy, label)).isOne();
        // không chép dự án con, yêu thích; bản gốc không đổi
        assertThat(jdbcTemplate.queryForObject("select count(*) from projects where parent_id = ?", Integer.class, copy))
                .isZero();
        assertThat(contents(project)).hasSize(8);
    }

    @Test
    void duplicate_englishName_andSubProjectStaysUnderSameParent() throws Exception {
        UUID child = jdbcTemplate.queryForObject("select id from projects where parent_id = ?", UUID.class, project);
        UUID copy = readUuid(mockMvc.perform(post(duplicate(child))
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Copy of Child"))
                .andExpect(jsonPath("$.parentId").value(project.toString())), "$.id");
        assertThat(copy).isNotEqualTo(child);
    }

    @Test
    void duplicate_longName_isCutTo120() throws Exception {
        String longName = "x".repeat(120);
        UUID p = testData.project(user.id(), longName);
        String name = readString(authPost(user.accessToken(), duplicate(p)).andExpect(status().isCreated()), "$.name");
        assertThat(name).hasSize(120).startsWith("Bản sao của x");
    }

    @Test
    void duplicate_errors() throws Exception {
        authPost(user.accessToken(), duplicate(UUID.randomUUID())).andExpect(status().isNotFound());
        RegisteredUser other = registerUser(uniqueEmail());
        authPost(other.accessToken(), duplicate(project)).andExpect(status().isNotFound());
        authPost(user.accessToken(), duplicate(testData.inboxOf(user.id()))).andExpect(status().isForbidden());
        UUID archived = testData.project(user.id(), "Cũ");
        testData.archiveProject(archived);
        authPost(user.accessToken(), duplicate(archived))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
    }

    @Test
    void duplicate_respectsProjectLimit() throws Exception {
        usageLimits.overrideMax(LimitKind.ACTIVE_PROJECTS, 3); // P, Sau, Child
        authPost(user.accessToken(), duplicate(project))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("limit-reached"))
                .andExpect(jsonPath("$.limit").value("ACTIVE_PROJECTS"));
    }

    @Test
    void duplicate_idempotencyKey_replaysSameCopy() throws Exception {
        String key = UUID.randomUUID().toString();
        String first = readString(mockMvc.perform(post(duplicate(project))
                .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                .header("Idempotency-Key", key)).andExpect(status().isCreated()), "$.id");
        mockMvc.perform(post(duplicate(project))
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(first));
        assertThat(jdbcTemplate.queryForObject("select count(*) from projects where name = 'Bản sao của Kế hoạch' "
                + "and workspace_id = ?", Integer.class, testData.workspaceOf(user.id()))).isOne();
    }
}
