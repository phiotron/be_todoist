package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import com.taskly.support.TestData.TaskRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * M11 F2 (nhân bản section) + F3 (Move section sang dự án khác).
 * <pre>
 * P: A (s-a: open ─ sub ; done (xong)) · B
 * Q: X
 * </pre>
 */
class SectionTransferApiTest extends IntegrationTestBase {

    private static final Instant DONE = Instant.parse("2026-10-20T03:00:00Z");

    private RegisteredUser user;
    private UUID p;
    private UUID q;
    private UUID a;
    private UUID b;
    private UUID open;
    private UUID sub;
    private UUID done;

    @BeforeEach
    void data() throws Exception {
        user = registerUser(uniqueEmail());
        p = testData.project(user.id(), "P");
        q = testData.project(user.id(), "Q");
        a = testData.section(p, "A");
        jdbcTemplate.update("update sections set description = 'mô tả A' where id = ?", a);
        b = testData.section(p, "B");
        testData.section(q, "X");
        open = testData.insert(TaskRow.of(user.id(), p, "open").section(a).sortOrder(1024));
        sub = testData.insert(TaskRow.of(user.id(), p, "sub").section(a).parent(open).sortOrder(1024));
        done = testData.insert(TaskRow.of(user.id(), p, "done").section(a).sortOrder(2048).completedAt(DONE));
    }

    private String sectionUri(UUID projectId, UUID sectionId, String action) {
        return "/api/v1/projects/" + projectId + "/sections/" + sectionId + "/" + action;
    }

    private List<String> sectionNames(UUID projectId) {
        return jdbcTemplate.queryForList("select name from sections where project_id = ? and archived_at is null "
                + "order by sort_order", String.class, projectId);
    }

    // ---- F2 duplicate ----

    @Test
    void duplicate_placesCopyRightAfter_withOpenTasksOnly() throws Exception {
        UUID copy = readUuid(authPost(user.accessToken(), sectionUri(p, a, "duplicate"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.projectId").value(p.toString()))
                .andExpect(jsonPath("$.name").value("A"))
                .andExpect(jsonPath("$.description").value("mô tả A"))
                .andExpect(jsonPath("$.activeTaskCount").value(2)), "$.id");
        assertThat(sectionNames(p)).containsExactly("A", "A", "B");
        List<String> copied = jdbcTemplate.queryForList("select content from tasks where section_id = ? order by content",
                String.class, copy);
        assertThat(copied).containsExactly("open", "sub");
        UUID copiedOpen = jdbcTemplate.queryForObject("select id from tasks where section_id = ? and content = 'open'",
                UUID.class, copy);
        assertThat(jdbcTemplate.queryForObject("select parent_id from tasks where section_id = ? and content = 'sub'",
                UUID.class, copy)).isEqualTo(copiedOpen);
    }

    @Test
    void duplicate_errorsAndLimits() throws Exception {
        authPost(user.accessToken(), sectionUri(q, a, "duplicate")).andExpect(status().isNotFound());
        RegisteredUser other = registerUser(uniqueEmail());
        authPost(other.accessToken(), sectionUri(p, a, "duplicate")).andExpect(status().isNotFound());

        usageLimits.overrideMax(LimitKind.PROJECT_SECTIONS, 2);
        authPost(user.accessToken(), sectionUri(p, a, "duplicate"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.limit").value("PROJECT_SECTIONS"));
        usageLimits.resetOverrides();
        usageLimits.overrideMax(LimitKind.PROJECT_ACTIVE_TASKS, 3);
        authPost(user.accessToken(), sectionUri(p, a, "duplicate"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.limit").value("PROJECT_ACTIVE_TASKS"));
        usageLimits.resetOverrides();

        testData.archiveSection(b);
        authPost(user.accessToken(), sectionUri(p, b, "duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));
        testData.archiveProject(p);
        authPost(user.accessToken(), sectionUri(p, a, "duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
    }

    // ---- F3 move ----

    @Test
    void move_toOtherProject_takesEveryTaskAlong_withoutVersionChange() throws Exception {
        authPost(user.accessToken(), sectionUri(p, a, "move"), "{\"projectId\": \"%s\"}".formatted(q))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(a.toString()))
                .andExpect(jsonPath("$.projectId").value(q.toString()))
                .andExpect(jsonPath("$.activeTaskCount").value(2));
        assertThat(sectionNames(q)).containsExactly("X", "A");
        assertThat(sectionNames(p)).containsExactly("B");
        for (UUID task : List.of(open, sub, done)) {
            assertThat(jdbcTemplate.queryForObject("select project_id from tasks where id = ?", UUID.class, task))
                    .isEqualTo(q);
            assertThat(jdbcTemplate.queryForObject("select section_id from tasks where id = ?", UUID.class, task))
                    .isEqualTo(a);
            assertThat(testData.taskVersion(task)).isZero();
        }
    }

    @Test
    void move_withAfterIdNull_goesFirst_andSameProjectOnlyReorders() throws Exception {
        authPost(user.accessToken(), sectionUri(p, a, "move"), "{\"projectId\": \"%s\", \"afterId\": null}".formatted(q))
                .andExpect(status().isOk());
        assertThat(sectionNames(q)).containsExactly("A", "X");

        authPost(user.accessToken(), sectionUri(p, b, "move"), "{\"projectId\": \"%s\"}".formatted(p))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(p.toString()));
        assertThat(sectionNames(p)).containsExactly("B");
    }

    @Test
    void move_errors() throws Exception {
        authPost(user.accessToken(), sectionUri(p, a, "move"), "{}").andExpect(status().isBadRequest());
        authPost(user.accessToken(), sectionUri(p, a, "move"), "{\"projectId\": \"%s\"}".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound());
        authPost(user.accessToken(), sectionUri(p, a, "move"),
                "{\"projectId\": \"%s\", \"afterId\": \"%s\"}".formatted(q, b))
                .andExpect(status().isBadRequest());
        RegisteredUser other = registerUser(uniqueEmail());
        UUID foreign = testData.project(other.id(), "Lạ");
        authPost(user.accessToken(), sectionUri(p, a, "move"), "{\"projectId\": \"%s\"}".formatted(foreign))
                .andExpect(status().isNotFound());

        UUID archivedTarget = testData.project(user.id(), "Đích cũ");
        testData.archiveProject(archivedTarget);
        authPost(user.accessToken(), sectionUri(p, a, "move"), "{\"projectId\": \"%s\"}".formatted(archivedTarget))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("target-archived"));

        usageLimits.overrideMax(LimitKind.PROJECT_ACTIVE_TASKS, 1);
        authPost(user.accessToken(), sectionUri(p, a, "move"), "{\"projectId\": \"%s\"}".formatted(q))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.limit").value("PROJECT_ACTIVE_TASKS"));
        usageLimits.resetOverrides();
        usageLimits.overrideMax(LimitKind.PROJECT_SECTIONS, 1);
        authPost(user.accessToken(), sectionUri(p, a, "move"), "{\"projectId\": \"%s\"}".formatted(q))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.limit").value("PROJECT_SECTIONS"));
        usageLimits.resetOverrides();

        testData.archiveSection(b);
        authPost(user.accessToken(), sectionUri(p, b, "move"), "{\"projectId\": \"%s\"}".formatted(q))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));
        assertThat(jdbcTemplate.queryForObject("select project_id from sections where id = ?", UUID.class, a))
                .isEqualTo(p);
    }
}
