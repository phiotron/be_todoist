package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.dto.task.TaskResponse;
import com.taskly.service.TaskViewService;
import com.taskly.support.TestData.TaskRow;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** tasks?projectId= : không section trước, rồi theo section; trong mỗi phần theo sort_order; kèm đủ cây con. */
class TaskProjectListingApiTest extends IntegrationTestBase {

    @Autowired
    private TaskViewService taskViewService;

    @Test
    void listsOpenRootsInDisplayOrderWithFullSubtrees_pagedByRoots() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID s1 = testData.section(project, "S1");
        UUID s2 = testData.section(project, "S2");
        testData.insert(TaskRow.of(user.id(), project, "N1").sortOrder(2048));
        testData.insert(TaskRow.of(user.id(), project, "N2").sortOrder(1024));
        UUID r1 = testData.insert(TaskRow.of(user.id(), project, "R1").section(s1));
        UUID r1a = testData.insert(TaskRow.of(user.id(), project, "R1a").section(s1).parent(r1).sortOrder(2048)
                .completedAt(clock.instant()));
        testData.insert(TaskRow.of(user.id(), project, "R1b").section(s1).parent(r1).sortOrder(1024));
        testData.insert(TaskRow.of(user.id(), project, "R1a-x").section(s1).parent(r1a));
        testData.insert(TaskRow.of(user.id(), project, "R2").section(s2));
        testData.insert(TaskRow.of(user.id(), project, "Gốc đã xong").completedAt(clock.instant()));

        String uri = "/api/v1/tasks?projectId=" + project;
        authGet(user.accessToken(), uri)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].content", contains("N2", "N1", "R1", "R1b", "R1a", "R1a-x", "R2")));

        String cursor = readString(authGet(user.accessToken(), uri + "&limit=2")
                .andExpect(jsonPath("$.items[*].content", contains("N2", "N1"))), "$.nextCursor");
        authGet(user.accessToken(), uri + "&limit=2&cursor=" + cursor)
                .andExpect(jsonPath("$.items[*].content", contains("R1", "R1b", "R1a", "R1a-x", "R2")))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void completedListing_showsCompletedRootsNewestFirst_includingArchivedSections_withFullSubtree() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID archivedSection = testData.section(project, "Lưu trữ");
        UUID oldRoot = testData.insert(TaskRow.of(user.id(), project, "Cũ")
                .completedAt(Instant.parse("2026-10-01T03:00:00Z")));
        testData.insert(TaskRow.of(user.id(), project, "Con của cũ").parent(oldRoot)
                .completedAt(Instant.parse("2026-10-01T03:00:00Z")));
        testData.insert(TaskRow.of(user.id(), project, "Mới").completedAt(Instant.parse("2026-10-20T03:00:00Z")));
        testData.insert(TaskRow.of(user.id(), project, "Trong section lưu trữ").section(archivedSection)
                .completedAt(Instant.parse("2026-10-10T03:00:00Z")));
        testData.archiveSection(archivedSection);
        testData.task(user.id(), project, "Đang mở");

        authGet(user.accessToken(), "/api/v1/tasks?projectId=" + project + "&completed=true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].content",
                        contains("Mới", "Trong section lưu trữ", "Cũ", "Con của cũ")));
        authGet(user.accessToken(), "/api/v1/tasks?projectId=" + project + "&completed=false")
                .andExpect(jsonPath("$.items[*].content", contains("Đang mở")));
    }

    @Test
    void inboxIsListedByItsProjectId_andArchivedProjectIsListable() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        testData.task(user.id(), inbox, "Trong Inbox");
        UUID archived = testData.project(user.id(), "Cũ");
        testData.task(user.id(), archived, "Việc cũ");
        testData.insert(TaskRow.of(user.id(), archived, "Đã xong cũ").completedAt(clock.instant()));
        testData.archiveProject(archived);

        authGet(user.accessToken(), "/api/v1/tasks?projectId=" + inbox)
                .andExpect(jsonPath("$.items[*].content", contains("Trong Inbox")));
        authGet(user.accessToken(), "/api/v1/tasks?projectId=" + archived)
                .andExpect(jsonPath("$.items[*].content", contains("Việc cũ")));
        authGet(user.accessToken(), "/api/v1/tasks?projectId=" + archived + "&completed=true")
                .andExpect(jsonPath("$.items[*].content", contains("Đã xong cũ")));
        authGet(user.accessToken(), "/api/v1/tasks?projectId=" + UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void subtasksEndpoint_returnsWholeSubtreeEvenUnderCompletedRoot() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID root = testData.insert(TaskRow.of(user.id(), project, "Gốc xong").completedAt(clock.instant()));
        UUID a = testData.insert(TaskRow.of(user.id(), project, "a").parent(root).sortOrder(2048)
                .completedAt(clock.instant()));
        testData.insert(TaskRow.of(user.id(), project, "b").parent(root).sortOrder(1024).completedAt(clock.instant()));
        testData.insert(TaskRow.of(user.id(), project, "a1").parent(a).completedAt(clock.instant()));

        authGet(user.accessToken(), "/api/v1/tasks/" + root + "/subtasks")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].content", contains("b", "a", "a1")));
        authGet(user.accessToken(), "/api/v1/tasks/" + a + "/subtasks").andExpect(jsonPath("$[*].content", contains("a1")));
        authGet(user.accessToken(), "/api/v1/tasks/" + UUID.randomUUID() + "/subtasks").andExpect(status().isNotFound());
        authGet(bob.accessToken(), "/api/v1/tasks/" + root + "/subtasks").andExpect(status().isNotFound());
        authGet(user.accessToken(), "/api/v1/tasks/" + root + "/subtasks").andExpect(jsonPath("$", not(empty())));
        // task không có con → danh sách rỗng, không phải 404
        UUID leaf = testData.task(user.id(), project, "Lá");
        authGet(user.accessToken(), "/api/v1/tasks/" + leaf + "/subtasks").andExpect(status().isOk())
                .andExpect(jsonPath("$", empty()));
    }

    /** Hàm cho M2: dựng TaskResponse theo đúng thứ tự id, bỏ id đã xoá / của người khác. */
    @Test
    void getResponsesInOrder_keepsGivenOrder_andSkipsInvisible() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID parent = testData.task(user.id(), project, "Cha");
        UUID child = testData.insert(TaskRow.of(user.id(), project, "Con").parent(parent));
        UUID deleted = testData.task(user.id(), project, "Xoá");
        testData.deleteTask(deleted);
        UUID foreign = testData.task(bob.id(), testData.inboxOf(bob.id()), "Của Bob");

        List<TaskResponse> responses = taskViewService.getResponsesInOrder(user.id(),
                List.of(child, deleted, foreign, parent));

        assertThat(responses).extracting(TaskResponse::id).containsExactly(child, parent);
        assertThat(responses.get(0).parentContent()).isEqualTo("Cha");
        assertThat(responses.get(0).projectName()).isEqualTo("P");
        assertThat(taskViewService.getResponsesInOrder(user.id(), List.of())).isEmpty();
    }
}
