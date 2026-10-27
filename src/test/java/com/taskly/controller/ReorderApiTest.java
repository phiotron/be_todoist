package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class ReorderApiTest extends IntegrationTestBase {

    private ResultActions reorder(String token, String type, UUID id, UUID afterId) throws Exception {
        String after = afterId == null ? "null" : "\"" + afterId + "\"";
        return authPost(token, "/api/v1/reorder", """
                {"type": "%s", "id": "%s", "afterId": %s}
                """.formatted(type, id, after));
    }

    private UUID createProject(RegisteredUser user, String name, UUID parentId) throws Exception {
        String parent = parentId == null ? "null" : "\"" + parentId + "\"";
        return readUuid(authPost(user.accessToken(), "/api/v1/projects",
                "{\"name\": \"%s\", \"parentId\": %s}".formatted(name, parent)).andExpect(status().isCreated()), "$.id");
    }

    @Test
    void projects_moveAfterAndToTop_inboxStaysFirstAndCannotMove() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = createProject(user, "A", null);
        UUID b = createProject(user, "B", null);
        UUID c = createProject(user, "C", null);

        reorder(user.accessToken(), "PROJECT", c, a)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("PROJECT"))
                .andExpect(jsonPath("$.id").value(c.toString()))
                .andExpect(jsonPath("$.sortOrder").value(1536));
        authGet(user.accessToken(), "/api/v1/projects")
                .andExpect(jsonPath("$[*].name", contains("Inbox", "A", "C", "B")));

        reorder(user.accessToken(), "PROJECT", b, null).andExpect(status().isOk());
        authGet(user.accessToken(), "/api/v1/projects")
                .andExpect(jsonPath("$[*].name", contains("Inbox", "B", "A", "C")));

        UUID inbox = testData.inboxOf(user.id());
        reorder(user.accessToken(), "PROJECT", inbox, null) // mọi thao tác nhắm vào chính Inbox → 403 (spec m2; bản 3 là 400)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Không thể xoá, lưu trữ, sửa, chuyển hay yêu thích Inbox"));
        reorder(user.accessToken(), "PROJECT", a, inbox)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("afterId phải cùng vùng chứa"));
    }

    @Test
    void subProjects_reorderOnlyAmongSiblingsOfTheSameParent() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID parent = createProject(user, "Cha", null);
        UUID other = createProject(user, "Khác", null);
        UUID c1 = createProject(user, "C1", parent);
        UUID c2 = createProject(user, "C2", parent);
        UUID c3 = createProject(user, "C3", parent);

        reorder(user.accessToken(), "PROJECT", c3, null).andExpect(status().isOk());
        authGet(user.accessToken(), "/api/v1/projects")
                .andExpect(jsonPath("$[*].name", contains("Inbox", "Cha", "C3", "C1", "C2", "Khác")));
        reorder(user.accessToken(), "PROJECT", c1, other) // anh em của dự án gốc, khác vùng chứa
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("afterId phải cùng vùng chứa"));
        assertThat(c2).isNotNull();
    }

    @Test
    void sections_reorderWithinProject_archivedSectionsCannotMoveNorBeAnchors() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID s1 = testData.section(project, "S1");
        UUID s2 = testData.section(project, "S2");
        UUID s3 = testData.section(project, "S3");
        UUID archived = testData.section(project, "Lưu trữ");
        testData.archiveSection(archived);

        reorder(user.accessToken(), "SECTION", s3, null).andExpect(status().isOk());
        authGet(user.accessToken(), "/api/v1/projects/" + project + "/sections")
                .andExpect(jsonPath("$[*].name", contains("S3", "S1", "S2", "Lưu trữ")));
        reorder(user.accessToken(), "SECTION", s1, testData.section(testData.project(user.id(), "Q"), "khác"))
                .andExpect(status().isBadRequest());
        reorder(user.accessToken(), "SECTION", archived, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));
        reorder(user.accessToken(), "SECTION", s1, archived).andExpect(status().isBadRequest());
        assertThat(s2).isNotNull();
    }

    @Test
    void tasks_renumberWhenNoGap_andAfterIdMustShareContainer() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID a = testData.insert(TaskRow.of(user.id(), project, "a").sortOrder(1024));
        UUID b = testData.insert(TaskRow.of(user.id(), project, "b").sortOrder(1025));
        UUID c = testData.insert(TaskRow.of(user.id(), project, "c").sortOrder(4096));
        UUID childOfA = testData.insert(TaskRow.of(user.id(), project, "a1").parent(a));

        reorder(user.accessToken(), "TASK", c, a)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sortOrder").value(2048));
        List<String> order = jdbcTemplate.queryForList("select content from tasks where project_id = ? "
                + "and parent_id is null order by sort_order", String.class, project);
        assertThat(order).containsExactly("a", "c", "b");
        assertThat(jdbcTemplate.queryForObject("select sort_order from tasks where id = ?", Long.class, b))
                .isEqualTo(3072L);
        // reorder / đánh số lại dùng native UPDATE: KHÔNG tăng version (spec 5.6, C4) nhưng vẫn ghi updated_at
        assertThat(testData.taskVersion(c)).isZero();
        assertThat(testData.taskVersion(b)).isZero();
        assertThat(testData.taskVersion(a)).isZero();

        reorder(user.accessToken(), "TASK", c, childOfA)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("afterId phải cùng vùng chứa"));
    }

    @Test
    void tasks_reorderWritesUpdatedAtButNotVersion() throws Exception {
        clock.freezeAt(java.time.Instant.parse("2026-10-23T03:00:00Z"));
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID a = testData.insert(TaskRow.of(user.id(), project, "a").sortOrder(1024));
        UUID b = testData.insert(TaskRow.of(user.id(), project, "b").sortOrder(2048));
        clock.freezeAt(java.time.Instant.parse("2026-10-23T03:05:00Z"));

        reorder(user.accessToken(), "TASK", b, null).andExpect(status().isOk());

        java.sql.Timestamp updatedAt = jdbcTemplate.queryForObject("select updated_at from tasks where id = ?",
                java.sql.Timestamp.class, b);
        assertThat(updatedAt.toInstant()).isEqualTo(java.time.Instant.parse("2026-10-23T03:05:00Z"));
        assertThat(testData.taskVersion(b)).isZero();
        assertThat(a).isNotNull();
    }

    @Test
    void validation_archived_andOtherUser() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID section = testData.section(project, "S");
        UUID task = testData.task(user.id(), project, "T");

        authPost(user.accessToken(), "/api/v1/reorder", "{\"id\": \"%s\"}".formatted(task))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("type", "afterId")));
        // afterId BẮT BUỘC có khoá (vắng ≠ null — spec I3): vắng → 400; "afterId": null → lên đầu (đã test ở trên)
        authPost(user.accessToken(), "/api/v1/reorder", "{\"type\": \"TASK\", \"id\": \"%s\"}".formatted(task))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("afterId"))
                .andExpect(jsonPath("$.errors[0].message").value("Thiếu afterId (gửi null để đặt lên đầu vùng chứa)"));
        reorder(bob.accessToken(), "PROJECT", project, null).andExpect(status().isNotFound());
        reorder(bob.accessToken(), "SECTION", section, null).andExpect(status().isNotFound());
        reorder(bob.accessToken(), "TASK", task, null).andExpect(status().isNotFound());

        testData.archiveProject(project);
        reorder(user.accessToken(), "TASK", task, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
        reorder(user.accessToken(), "SECTION", section, null).andExpect(status().isConflict());
        reorder(user.accessToken(), "PROJECT", project, null).andExpect(status().isConflict());
    }
}
