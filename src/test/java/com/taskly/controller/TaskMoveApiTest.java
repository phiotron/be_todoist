package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import com.taskly.support.TestData.TaskRow;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class TaskMoveApiTest extends IntegrationTestBase {

    private RegisteredUser user;
    private UUID source;
    private UUID target;
    private UUID root;
    private UUID child;

    @BeforeEach
    void data() throws Exception {
        user = registerUser(uniqueEmail());
        source = testData.project(user.id(), "Nguồn");
        target = testData.project(user.id(), "Đích");
        UUID sourceSection = testData.section(source, "S nguồn");
        root = testData.insert(TaskRow.of(user.id(), source, "Gốc").section(sourceSection));
        child = testData.insert(TaskRow.of(user.id(), source, "Con").section(sourceSection).parent(root));
    }

    private ResultActions move(String token, UUID taskId, String json) throws Exception {
        return authPost(token, "/api/v1/tasks/" + taskId + "/move", json);
    }

    @Test
    void moveToOtherProject_takesSubtreeAlong_andGoesToEnd() throws Exception {
        testData.insert(TaskRow.of(user.id(), target, "Có sẵn").sortOrder(5000));

        move(user.accessToken(), root, "{\"projectId\": \"%s\"}".formatted(target))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(target.toString()))
                .andExpect(jsonPath("$.sectionId").value(nullValue()))
                .andExpect(jsonPath("$.projectName").value("Đích"))
                .andExpect(jsonPath("$.sortOrder").value(6024));
        authGet(user.accessToken(), "/api/v1/tasks/" + child)
                .andExpect(jsonPath("$.projectId").value(target.toString()))
                .andExpect(jsonPath("$.sectionId").value(nullValue()))
                .andExpect(jsonPath("$.parentId").value(root.toString()));
    }

    @Test
    void move_bumpsOnlyTheMovedTasksVersion_subtreeIsRelocatedNatively() throws Exception {
        Instant childUpdatedBefore = testData.taskUpdatedAt(child);
        clock.advance(Duration.ofMinutes(5));

        move(user.accessToken(), root, "{\"projectId\": \"%s\"}".formatted(target))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1)); // task ĐƯỢC chuyển: entity → version + 1

        assertThat(testData.taskVersion(root)).isEqualTo(1);
        assertThat(testData.taskVersion(child)).as("con cháu đi theo bằng native UPDATE: version không đổi").isZero();
        assertThat(jdbcTemplate.queryForObject("select project_id from tasks where id = ?", UUID.class, child))
                .isEqualTo(target);
        assertThat(testData.taskWorkspace(child)).isEqualTo(testData.workspaceOf(user.id()));
        assertThat(testData.taskUpdatedAt(child)).as("native UPDATE vẫn ghi updated_at").isAfter(childUpdatedBefore);
    }

    @Test
    void moveIntoSection_andBackToInbox() throws Exception {
        UUID section = testData.section(target, "S đích");
        move(user.accessToken(), root, "{\"projectId\": \"%s\", \"sectionId\": \"%s\"}".formatted(target, section))
                .andExpect(jsonPath("$.sectionId").value(section.toString()));
        authGet(user.accessToken(), "/api/v1/tasks/" + child).andExpect(jsonPath("$.sectionId").value(section.toString()));

        UUID inbox = testData.inboxOf(user.id());
        move(user.accessToken(), root, "{\"projectId\": \"%s\"}".formatted(inbox))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectName").value("Inbox"))
                .andExpect(jsonPath("$.sectionId").value(nullValue()));
    }

    @Test
    void moveUnderParent_checksDepthCycleAndCompletedParent() throws Exception {
        UUID q = testData.task(user.id(), target, "q");
        UUID q1 = testData.insert(TaskRow.of(user.id(), target, "q1").parent(q));
        UUID q2 = testData.insert(TaskRow.of(user.id(), target, "q2").parent(q1));
        UUID done = testData.insert(TaskRow.of(user.id(), target, "Đã xong").completedAt(clock.instant()));
        String under = "{\"projectId\": \"%s\", \"parentId\": \"%s\"}";

        move(user.accessToken(), root, under.formatted(target, q2)) // 3 + cao 2 = 5 cấp
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Công việc con tối đa 4 cấp"));
        move(user.accessToken(), root, under.formatted(target, done))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("parent-completed"));
        move(user.accessToken(), root, under.formatted(target, q1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentId").value(q1.toString()));
        move(user.accessToken(), q, under.formatted(target, q2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Không thể đặt công việc làm con của chính nó hoặc của con cháu nó"));
        move(user.accessToken(), q, under.formatted(source, child))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Công việc cha phải cùng dự án"));
    }

    @Test
    void targetRules_422_404_400() throws Exception {
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID archived = testData.project(user.id(), "Lưu trữ");
        testData.archiveProject(archived);
        UUID archivedSection = testData.section(target, "Lưu trữ");
        testData.archiveSection(archivedSection);
        UUID goneSection = testData.section(target, "Đã xoá");
        testData.deleteSection(goneSection);
        UUID sectionOfSource = testData.section(source, "Của nguồn");
        String toProject = "{\"projectId\": \"%s\"}";
        String toSection = "{\"projectId\": \"%s\", \"sectionId\": \"%s\"}";

        move(user.accessToken(), root, toProject.formatted(archived))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("target-archived"));
        move(user.accessToken(), root, toProject.formatted(testData.inboxOf(bob.id()))).andExpect(status().isNotFound());
        move(user.accessToken(), root, toSection.formatted(target, archivedSection))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("target-archived"));
        move(user.accessToken(), root, toSection.formatted(target, goneSection)).andExpect(status().isNotFound());
        move(user.accessToken(), root, toSection.formatted(target, sectionOfSource))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Section không thuộc dự án đích"));
        move(user.accessToken(), root, "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Thiếu projectId"));
    }

    @Test
    void afterIdPlacesInsideTargetContainer() throws Exception {
        UUID first = testData.insert(TaskRow.of(user.id(), target, "a").sortOrder(1024));
        testData.insert(TaskRow.of(user.id(), target, "b").sortOrder(2048));

        move(user.accessToken(), root, "{\"projectId\": \"%s\", \"afterId\": \"%s\"}".formatted(target, first))
                .andExpect(jsonPath("$.sortOrder").value(1536));
        move(user.accessToken(), root, "{\"projectId\": \"%s\", \"afterId\": \"%s\"}".formatted(target, child))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("afterId phải cùng vùng chứa"));
    }

    @Test
    void afterIdNull_goesToTopOfTargetContainer_absentGoesToEnd() throws Exception {
        testData.insert(TaskRow.of(user.id(), target, "a").sortOrder(1024));
        testData.insert(TaskRow.of(user.id(), target, "b").sortOrder(2048));

        // "afterId": null tường minh = ĐẦU vùng chứa (khác với vắng = cuối)
        move(user.accessToken(), root, "{\"projectId\": \"%s\", \"afterId\": null}".formatted(target))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sortOrder").value(512));
        // vắng afterId = cuối vùng chứa (root đã nằm trong dự án đích, vùng chứa không tính chính nó)
        move(user.accessToken(), root, "{\"projectId\": \"%s\"}".formatted(target))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sortOrder").value(3072));
        assertThat(testData.taskVersion(root)).as("lần 2 đổi sort_order bằng native: không thêm version").isEqualTo(1);
    }

    @Test
    void movingIntoAnotherProject_respectsOpenTaskLimit_ofTarget() throws Exception {
        testData.task(user.id(), target, "Có sẵn");
        usageLimits.overrideMax(LimitKind.PROJECT_ACTIVE_TASKS, 2);

        move(user.accessToken(), root, "{\"projectId\": \"%s\"}".formatted(target)) // 1 + (gốc + con) > 2
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.limit").value("PROJECT_ACTIVE_TASKS"));
        move(user.accessToken(), root, "{\"projectId\": \"%s\"}".formatted(source)).andExpect(status().isOk()); // cùng dự án
    }

    @Test
    void archivedSourceOrSection409_otherUser404() throws Exception {
        RegisteredUser bob = registerUser(uniqueEmail());
        move(bob.accessToken(), root, "{\"projectId\": \"%s\"}".formatted(testData.inboxOf(bob.id())))
                .andExpect(status().isNotFound());

        UUID section = testData.section(source, "Lưu trữ");
        UUID inArchived = testData.insert(TaskRow.of(user.id(), source, "Trong section").section(section));
        testData.archiveSection(section);
        move(user.accessToken(), inArchived, "{\"projectId\": \"%s\"}".formatted(target))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));

        testData.archiveProject(source);
        move(user.accessToken(), root, "{\"projectId\": \"%s\"}".formatted(target))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
        // Kiểm NGUỒN trước ĐÍCH: nguồn lưu trữ + đích lưu trữ → 409 (không phải 422)
        UUID archivedTarget = testData.project(user.id(), "Đích lưu trữ");
        testData.archiveProject(archivedTarget);
        move(user.accessToken(), root, "{\"projectId\": \"%s\"}".formatted(archivedTarget))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
    }

    @Test
    void completedTask_cannotBeMoved_409() throws Exception {
        UUID done = testData.insert(TaskRow.of(user.id(), source, "Đã xong").completedAt(clock.instant()));
        move(user.accessToken(), done, "{\"projectId\": \"%s\"}".formatted(target))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("task-completed"));
        assertThat(testData.taskVersion(done)).isZero();
    }

    @Test
    void parentInArchivedSection_409_notTargetArchived() throws Exception {
        UUID section = testData.section(target, "Sắp lưu trữ");
        UUID parent = testData.insert(TaskRow.of(user.id(), target, "Cha").section(section));
        testData.archiveSection(section);
        move(user.accessToken(), root, "{\"projectId\": \"%s\", \"parentId\": \"%s\"}".formatted(target, parent))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));
    }
}
