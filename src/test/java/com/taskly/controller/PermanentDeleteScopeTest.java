package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Xoá task / section / dự án / lô chọn nhiều xoá ĐÚNG phần kèm theo (cây con, task, section, dự án con, yêu thích)
 * và không đụng dữ liệu nào khác — kể cả của user khác (spec 17: "test kỹ phạm vi xoá").
 */
class PermanentDeleteScopeTest extends IntegrationTestBase {

    /** Ảnh chụp số dòng của MỘT user trên mọi bảng M1. */
    private Map<String, Integer> snapshot(UUID userId) {
        UUID workspace = testData.workspaceOf(userId);
        return Map.of(
                "projects", count("select count(*) from projects where workspace_id = ?", workspace),
                "sections", count("select count(*) from sections s join projects p on p.id = s.project_id "
                        + "where p.workspace_id = ?", workspace),
                "tasks", count("select count(*) from tasks t join projects p on p.id = t.project_id "
                        + "where p.workspace_id = ?", workspace),
                "favorites", count("select count(*) from favorites where user_id = ?", userId));
    }

    private int count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private UUID createProject(RegisteredUser user, String name, UUID parentId) throws Exception {
        String parent = parentId == null ? "null" : "\"" + parentId + "\"";
        return readUuid(authPost(user.accessToken(), "/api/v1/projects",
                "{\"name\": \"%s\", \"parentId\": %s}".formatted(name, parent)).andExpect(status().isCreated()), "$.id");
    }

    /** Dự án gốc + dự án con + section + cây task 3 cấp + yêu thích; trả id dự án gốc. */
    private UUID buildTree(RegisteredUser user, String prefix) throws Exception {
        UUID root = createProject(user, prefix + "-gốc", null);
        UUID child = createProject(user, prefix + "-con", root);
        UUID section = testData.section(child, prefix + "-section");
        UUID t1 = testData.insert(TaskRow.of(user.id(), child, prefix + "-t1").section(section));
        UUID t2 = testData.insert(TaskRow.of(user.id(), child, prefix + "-t2").section(section).parent(t1));
        testData.insert(TaskRow.of(user.id(), child, prefix + "-t3").section(section).parent(t2)
                .completedAt(clock.instant()));
        testData.task(user.id(), root, prefix + "-t4");
        authPut(user.accessToken(), "/api/v1/projects/" + child + "/favorite").andExpect(status().isOk());
        return root;
    }

    @Test
    void deletingProjectTree_removesOnlyThatTree_notSiblingsNorOtherUsers() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID doomed = buildTree(alice, "xoá");
        UUID kept = buildTree(alice, "giữ");
        List<UUID> doomedProjects = jdbcTemplate.queryForList(
                "select id from projects where id = ? or parent_id = ?", UUID.class, doomed, doomed);
        List<UUID> keptProjects = jdbcTemplate.queryForList(
                "select id from projects where id = ? or parent_id = ?", UUID.class, kept, kept);
        assertThat(doomedProjects).hasSize(2);
        buildTree(bob, "bob");
        Map<String, Integer> aliceBefore = snapshot(alice.id());
        Map<String, Integer> bobBefore = snapshot(bob.id());

        authDelete(alice.accessToken(), "/api/v1/projects/" + doomed).andExpect(status().isNoContent());

        Map<String, Integer> aliceAfter = snapshot(alice.id());
        assertThat(aliceAfter.get("projects")).isEqualTo(aliceBefore.get("projects") - 2); // gốc + con
        assertThat(aliceAfter.get("sections")).isEqualTo(aliceBefore.get("sections") - 1);
        assertThat(aliceAfter.get("tasks")).isEqualTo(aliceBefore.get("tasks") - 4);
        assertThat(aliceAfter.get("favorites")).isEqualTo(aliceBefore.get("favorites") - 1);
        assertThat(snapshot(bob.id())).isEqualTo(bobBefore);
        // đếm THEO project_id, không đếm toàn cục theo nội dung
        for (UUID gone : doomedProjects) {
            assertThat(count("select count(*) from tasks where project_id = ?", gone)).isZero();
            assertThat(count("select count(*) from projects where id = ?", gone)).isZero();
        }
        int keptTasks = 0;
        for (UUID survivor : keptProjects) {
            keptTasks += count("select count(*) from tasks where project_id = ?", survivor);
        }
        assertThat(keptTasks).isEqualTo(4);
    }

    @Test
    void deletingSection_removesItsTasksOnly() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID project = testData.project(alice.id(), "P");
        UUID doomed = testData.section(project, "Xoá");
        UUID kept = testData.section(project, "Giữ");
        UUID root = testData.insert(TaskRow.of(alice.id(), project, "a").section(doomed));
        testData.insert(TaskRow.of(alice.id(), project, "a1").section(doomed).parent(root));
        testData.insert(TaskRow.of(alice.id(), project, "xong").section(doomed).completedAt(clock.instant()));
        testData.insert(TaskRow.of(alice.id(), project, "b").section(kept));
        testData.task(alice.id(), project, "c");
        buildTree(bob, "bob");
        Map<String, Integer> bobBefore = snapshot(bob.id());

        authDelete(alice.accessToken(), "/api/v1/projects/" + project + "/sections/" + doomed)
                .andExpect(status().isNoContent());

        assertThat(count("select count(*) from tasks where project_id = ?", project)).isEqualTo(2);
        assertThat(count("select count(*) from sections where project_id = ?", project)).isOne();
        assertThat(snapshot(bob.id())).isEqualTo(bobBefore);
    }

    @Test
    void deletingTaskAndBatchDelete_removeOnlyTheirSubtrees() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        UUID project = testData.project(alice.id(), "P");
        UUID r1 = testData.task(alice.id(), project, "r1");
        UUID r1a = testData.insert(TaskRow.of(alice.id(), project, "r1a").parent(r1));
        testData.insert(TaskRow.of(alice.id(), project, "r1a1").parent(r1a));
        UUID r2 = testData.task(alice.id(), project, "r2");
        testData.insert(TaskRow.of(alice.id(), project, "r2a").parent(r2));
        UUID r3 = testData.task(alice.id(), project, "r3");
        testData.task(alice.id(), project, "giữ");

        authDelete(alice.accessToken(), "/api/v1/tasks/" + r1a).andExpect(status().isNoContent());
        assertThat(count("select count(*) from tasks where project_id = ?", project)).isEqualTo(5); // 7 task - r1a - r1a1

        authPost(alice.accessToken(), "/api/v1/tasks/batch",
                "{\"action\": \"DELETE\", \"taskIds\": [\"%s\", \"%s\"]}".formatted(r2, r3)).andExpect(status().isOk());

        List<String> left = jdbcTemplate.queryForList("select content from tasks where project_id = ? order by content",
                String.class, project);
        assertThat(left).containsExactly("giữ", "r1");
    }
}
