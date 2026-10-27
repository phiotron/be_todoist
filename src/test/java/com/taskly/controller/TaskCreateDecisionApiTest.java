package com.taskly.controller;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import com.taskly.support.TestData.TaskRow;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Bảng quyết định POST tasks (spec 12.3), kiểm THEO THỨ TỰ, dòng đầu khớp thì dừng — mỗi dòng một test:
 * 1 `id` đã có (TaskCreateIdApiTest) · 2 → 404 · 3 → 400 · 4 → 409 project-archived · 5 → 409 section-archived ·
 * 6 → 409 parent-completed · 7 → 422 target-archived (dự án) · 8 → 422 target-archived (section) · 9 → 422
 * limit-reached. Cuối cùng là test THỨ TỰ giữa các dòng.
 */
class TaskCreateDecisionApiTest extends IntegrationTestBase {

    private static final String TASKS = "/api/v1/tasks";

    private RegisteredUser user;
    private UUID project;
    private UUID section;

    @BeforeEach
    void data() throws Exception {
        user = registerUser(uniqueEmail());
        project = testData.project(user.id(), "P");
        section = testData.section(project, "S");
    }

    private ResultActions create(String json) throws Exception {
        return authPost(user.accessToken(), TASKS, json);
    }

    /** Chuỗi cha–con 4 cấp trong dự án: trả id task cấp 4. */
    private UUID chainOfFour(UUID inProject) {
        UUID l1 = testData.task(user.id(), inProject, "1");
        UUID l2 = testData.insert(TaskRow.of(user.id(), inProject, "2").parent(l1));
        UUID l3 = testData.insert(TaskRow.of(user.id(), inProject, "3").parent(l2));
        return testData.insert(TaskRow.of(user.id(), inProject, "4").parent(l3));
    }

    @Test
    void row2_missingOrForeignParentProjectOrSection_is404() throws Exception {
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID bobTask = testData.task(bob.id(), testData.inboxOf(bob.id()), "Của Bob");
        UUID goneTask = testData.task(user.id(), project, "Đã xoá");
        testData.deleteTask(goneTask);
        UUID goneSection = testData.section(project, "S đã xoá");
        testData.deleteSection(goneSection);

        create("{\"parentId\": \"%s\", \"content\": \"x\"}".formatted(goneTask))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Không tìm thấy công việc"));
        create("{\"parentId\": \"%s\", \"content\": \"x\"}".formatted(bobTask)).andExpect(status().isNotFound());
        create("{\"projectId\": \"%s\", \"content\": \"x\"}".formatted(testData.inboxOf(bob.id())))
                .andExpect(status().isNotFound());
        create("{\"projectId\": \"%s\", \"sectionId\": \"%s\", \"content\": \"x\"}".formatted(project, goneSection))
                .andExpect(status().isNotFound());
    }

    @Test
    void row3_parentWithDifferentProjectOrSection_is400_butMatchingOnesAreAccepted() throws Exception {
        UUID other = testData.project(user.id(), "Q");
        UUID section2 = testData.section(project, "S2");
        UUID parent = testData.insert(TaskRow.of(user.id(), project, "Cha").section(section));

        create("{\"projectId\": \"%s\", \"parentId\": \"%s\", \"content\": \"x\"}".formatted(other, parent))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Công việc cha phải cùng dự án"));
        create("{\"projectId\": \"%s\", \"sectionId\": \"%s\", \"parentId\": \"%s\", \"content\": \"x\"}"
                .formatted(project, section2, parent))
                .andExpect(status().isBadRequest()); // con luôn cùng section với cha (PARENT_NOT_IN_SECTION)
        create("{\"projectId\": \"%s\", \"sectionId\": \"%s\", \"parentId\": \"%s\", \"content\": \"khớp cha\"}"
                .formatted(project, section, parent))
                .andExpect(status().isCreated());
        create("{\"parentId\": \"%s\", \"content\": \"thừa hưởng\"}".formatted(parent))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sectionId").value(section.toString()));
    }

    @Test
    void row3_sectionOfAnotherProject_is400_andFifthLevel_is400() throws Exception {
        UUID sectionOfOther = testData.section(testData.project(user.id(), "Q"), "S khác");
        create("{\"projectId\": \"%s\", \"sectionId\": \"%s\", \"content\": \"x\"}".formatted(project, sectionOfOther))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Section không thuộc dự án đích"));

        UUID level1 = testData.task(user.id(), project, "1");
        UUID level2 = testData.insert(TaskRow.of(user.id(), project, "2").parent(level1));
        UUID level3 = testData.insert(TaskRow.of(user.id(), project, "3").parent(level2));
        UUID level4 = readUuid(create("{\"parentId\": \"%s\", \"content\": \"4\"}".formatted(level3))
                .andExpect(status().isCreated()), "$.id"); // cấp 4: vẫn được
        create("{\"parentId\": \"%s\", \"content\": \"5\"}".formatted(level4))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Công việc con tối đa 4 cấp"));
    }

    @Test
    void row4_parentInArchivedProject_is409ProjectArchived() throws Exception {
        UUID parent = testData.task(user.id(), project, "Cha");
        testData.archiveProject(project);

        create("{\"parentId\": \"%s\", \"content\": \"x\"}".formatted(parent))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
        create("{\"projectId\": \"%s\", \"parentId\": \"%s\", \"content\": \"x\"}".formatted(project, parent))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived")); // cha quyết định, không phải 422
    }

    @Test
    void row5_parentInArchivedSection_is409SectionArchived() throws Exception {
        UUID parent = testData.insert(TaskRow.of(user.id(), project, "Cha").section(section));
        testData.archiveSection(section);

        create("{\"parentId\": \"%s\", \"content\": \"x\"}".formatted(parent))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));
    }

    @Test
    void row6_completedParent_is409ParentCompleted() throws Exception {
        UUID done = testData.insert(TaskRow.of(user.id(), project, "Đã xong").completedAt(clock.instant()));

        create("{\"parentId\": \"%s\", \"content\": \"x\"}".formatted(done))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("parent-completed"));
    }

    @Test
    void row7_noParent_archivedProject_is422TargetArchived() throws Exception {
        UUID archived = testData.project(user.id(), "Lưu trữ");
        UUID sectionInArchived = testData.section(archived, "S");
        testData.archiveProject(archived);

        create("{\"projectId\": \"%s\", \"content\": \"x\"}".formatted(archived))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value("Nơi chuyển đến đang được lưu trữ"))
                .andExpect(jsonPath("$.code").value("target-archived"));
        create("{\"projectId\": \"%s\", \"sectionId\": \"%s\", \"content\": \"x\"}".formatted(archived, sectionInArchived))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("target-archived")); // dự án của section lưu trữ
    }

    @Test
    void row8_noParent_archivedSection_is422TargetArchived() throws Exception {
        testData.archiveSection(section);

        create("{\"projectId\": \"%s\", \"sectionId\": \"%s\", \"content\": \"x\"}".formatted(project, section))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("target-archived"));
    }

    @Test
    void row9_overOpenTaskLimit_is422LimitReached_completedOnesDoNotCount() throws Exception {
        usageLimits.overrideMax(LimitKind.PROJECT_ACTIVE_TASKS, 2);
        UUID first = testData.task(user.id(), project, "1");
        testData.insert(TaskRow.of(user.id(), project, "2").parent(first)); // sub-task cũng tính
        testData.insert(TaskRow.of(user.id(), project, "Xong").completedAt(clock.instant()));
        String body = "{\"projectId\": \"%s\", \"content\": \"3\"}".formatted(project);

        create(body)
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("limit-reached"))
                .andExpect(jsonPath("$.limit").value("PROJECT_ACTIVE_TASKS"))
                .andExpect(jsonPath("$.max").value(2));
        jdbcTemplate.update("update tasks set completed_at = now() where id = ?", first);
        create(body).andExpect(status().isCreated());
    }

    @Test
    void checkOrder_notFound_then400_then409InTableOrder_then422_thenLimit() throws Exception {
        UUID archivedProject = testData.project(user.id(), "Q");
        UUID level4 = chainOfFour(archivedProject);
        UUID doneInArchivedProject = testData.insert(TaskRow.of(user.id(), archivedProject, "Xong")
                .completedAt(clock.instant()));
        UUID goneSection = testData.section(archivedProject, "S đã xoá");
        testData.deleteSection(goneSection);
        testData.archiveProject(archivedProject);
        UUID doneInArchivedSection = testData.insert(TaskRow.of(user.id(), project, "Xong").section(section)
                .completedAt(clock.instant()));
        testData.archiveSection(section);

        // 400 (cấp thứ 5) đứng trước 409 project-archived
        create("{\"parentId\": \"%s\", \"content\": \"x\"}".formatted(level4)).andExpect(status().isBadRequest());
        // 409 project-archived đứng trước 409 parent-completed (cha vừa xong vừa ở dự án lưu trữ)
        create("{\"parentId\": \"%s\", \"content\": \"x\"}".formatted(doneInArchivedProject))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("project-archived"));
        // 409 section-archived đứng trước 409 parent-completed (cha vừa xong vừa ở section lưu trữ)
        create("{\"parentId\": \"%s\", \"content\": \"x\"}".formatted(doneInArchivedSection))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("section-archived"));
        // 404 đứng trước 422 (dự án đích lưu trữ nhưng section không tồn tại)
        create("{\"projectId\": \"%s\", \"sectionId\": \"%s\", \"content\": \"x\"}".formatted(archivedProject, goneSection))
                .andExpect(status().isNotFound());
        // 422 target-archived đứng trước 422 limit-reached
        usageLimits.overrideMax(LimitKind.PROJECT_ACTIVE_TASKS, 0);
        create("{\"projectId\": \"%s\", \"content\": \"x\"}".formatted(archivedProject))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("target-archived"));
        create("{\"projectId\": \"%s\", \"content\": \"x\"}".formatted(project))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("limit-reached"));
    }
}
