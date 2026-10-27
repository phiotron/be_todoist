package com.taskly.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** GET tasks?view=completed (spec 12.3, C1 / C2): màn Completed toàn cục — xuyên dự án, mỗi task một dòng, mới xong trước. */
class TaskCompletedViewApiTest extends IntegrationTestBase {

    private static final String COMPLETED = "/api/v1/tasks?view=completed";

    @Test
    void listsCompletedTasksOfEveryProject_subtasksAsOwnRows_newestFirst_archivedProjectsExcluded() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        UUID p1 = testData.project(user.id(), "P1");
        UUID p2 = testData.project(user.id(), "P2");
        UUID archivedProject = testData.project(user.id(), "Đã lưu trữ");
        UUID archivedSection = testData.section(p2, "S lưu trữ");
        testData.insert(TaskRow.of(user.id(), inbox, "A").completedAt(Instant.parse("2026-10-20T03:00:00Z")));
        UUID b = testData.insert(TaskRow.of(user.id(), p1, "B").completedAt(Instant.parse("2026-10-22T03:00:00Z")));
        testData.insert(TaskRow.of(user.id(), p1, "B1").parent(b).completedAt(Instant.parse("2026-10-21T03:00:00Z")));
        testData.insert(TaskRow.of(user.id(), p2, "C").section(archivedSection)
                .completedAt(Instant.parse("2026-10-23T03:00:00Z")));
        testData.insert(TaskRow.of(user.id(), archivedProject, "D").completedAt(Instant.parse("2026-10-24T03:00:00Z")));
        testData.task(user.id(), p1, "E đang mở");
        testData.archiveSection(archivedSection);
        testData.archiveProject(archivedProject);

        authGet(user.accessToken(), COMPLETED)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].content", contains("C", "B", "B1", "A")))   // C trong section lưu trữ vẫn có; D bị loại
                .andExpect(jsonPath("$.items[?(@.content == 'B1')].parentContent", contains("B")))
                .andExpect(jsonPath("$.items[?(@.content == 'B1')].projectName", contains("P1")))
                .andExpect(jsonPath("$.items[0].completedAt").value("2026-10-23T03:00:00Z"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void sinceAndUntil_areDaysInTheAccountTimeZone_inclusiveAtBothEnds() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail()); // Asia/Ho_Chi_Minh = UTC+7
        UUID inbox = testData.inboxOf(user.id());
        testData.insert(TaskRow.of(user.id(), inbox, "A").completedAt(Instant.parse("2026-10-20T03:00:00Z"))); // 10:00 ngày 20
        testData.insert(TaskRow.of(user.id(), inbox, "F").completedAt(Instant.parse("2026-10-20T17:30:00Z"))); // 00:30 ngày 21
        testData.insert(TaskRow.of(user.id(), inbox, "G").completedAt(Instant.parse("2026-10-21T16:59:00Z"))); // 23:59 ngày 21
        testData.insert(TaskRow.of(user.id(), inbox, "H").completedAt(Instant.parse("2026-10-21T17:00:00Z"))); // 00:00 ngày 22

        authGet(user.accessToken(), COMPLETED + "&since=2026-10-21&until=2026-10-21")
                .andExpect(jsonPath("$.items[*].content", contains("G", "F")));
        authGet(user.accessToken(), COMPLETED + "&since=2026-10-21")
                .andExpect(jsonPath("$.items[*].content", contains("H", "G", "F")));
        authGet(user.accessToken(), COMPLETED + "&until=2026-10-20")
                .andExpect(jsonPath("$.items[*].content", contains("A")));
        authGet(user.accessToken(), COMPLETED + "&since=2026-10-23").andExpect(jsonPath("$.items", empty()));
    }

    @Test
    void sinceAndUntil_followTheAccountTimeZoneWhenItChanges() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        // 2026-10-21T03:00Z = ngày 21 ở Việt Nam, nhưng 23:00 ngày 20 ở New York (UTC-4 mùa hè).
        testData.insert(TaskRow.of(user.id(), inbox, "X").completedAt(Instant.parse("2026-10-21T03:00:00Z")));
        testData.setTimeZone(user.id(), "America/New_York");

        authGet(user.accessToken(), COMPLETED + "&since=2026-10-20&until=2026-10-20")
                .andExpect(jsonPath("$.items[*].content", contains("X")));
        authGet(user.accessToken(), COMPLETED + "&since=2026-10-21&until=2026-10-21")
                .andExpect(jsonPath("$.items", empty()));
    }

    @Test
    void sinceAfterUntil_is400_andOtherUsersSeeOnlyTheirOwn() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        testData.insert(TaskRow.of(alice.id(), testData.inboxOf(alice.id()), "Của Alice").completedAt(clock.instant()));

        authGet(alice.accessToken(), COMPLETED + "&since=2026-10-02&until=2026-10-01")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("since không được sau until"));
        authGet(bob.accessToken(), COMPLETED).andExpect(status().isOk()).andExpect(jsonPath("$.items", empty()));
    }
}
