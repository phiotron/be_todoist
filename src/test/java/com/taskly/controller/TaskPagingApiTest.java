package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.common.web.CursorCodec;
import com.taskly.support.TestData.TaskRow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Phân trang con trỏ (spec 5.10): không trùng, không sót khi dữ liệu đổi giữa hai lần tải; lỗi tham số → 400. */
class TaskPagingApiTest extends IntegrationTestBase {

    private static final LocalDate DAY0 = LocalDate.of(2026, 10, 20);

    private record PageResult(List<String> ids, String nextCursor) {
    }

    private PageResult page(RegisteredUser user, String query) throws Exception {
        String json = body(authGet(user.accessToken(), "/api/v1/tasks?" + query).andExpect(status().isOk()).andReturn());
        return new PageResult(JsonPath.read(json, "$.items[*].id"), JsonPath.read(json, "$.nextCursor"));
    }

    /** count task, task i đến hạn DAY0 + i ngày. */
    private List<UUID> createDated(RegisteredUser user, UUID project, int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(testData.insert(TaskRow.of(user.id(), project, "T" + i).dueDate(DAY0.plusDays(i))));
        }
        return ids;
    }

    private List<String> walk(RegisteredUser user, String baseQuery, int limit) throws Exception {
        List<String> seen = new ArrayList<>();
        String cursor = null;
        do {
            PageResult result = page(user, baseQuery + "&limit=" + limit + (cursor == null ? "" : "&cursor=" + cursor));
            seen.addAll(result.ids());
            cursor = result.nextCursor();
        } while (cursor != null);
        return seen;
    }

    @Test
    void walkingAllPages_returnsEveryTaskOnceInOrder() throws Exception {
        clock.freezeAt(Instant.parse("2026-10-20T03:00:00Z")); // 10:00 ngày 20 giờ Việt Nam
        RegisteredUser user = registerUser(uniqueEmail());
        List<UUID> created = createDated(user, testData.project(user.id(), "P"), 7);

        List<String> seen = walk(user, "view=upcoming&from=2026-10-20", 3);

        assertThat(seen).containsExactlyElementsOf(created.stream().map(UUID::toString).toList());
        assertThat(page(user, "view=upcoming&from=2026-10-20&limit=3").nextCursor()).isNotNull();
    }

    @Test
    void manyTasksOnTheSameDay_tieBreakByIdKeepsPagesDisjoint() throws Exception {
        clock.freezeAt(Instant.parse("2026-10-20T03:00:00Z"));
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        Set<String> created = new HashSet<>();
        for (int i = 0; i < 9; i++) {
            created.add(testData.insert(TaskRow.of(user.id(), project, "Cùng ngày " + i).dueDate(DAY0)
                    .sortOrder(1024)).toString());
        }

        List<String> seen = walk(user, "view=today", 2);

        assertThat(seen).hasSize(9).doesNotHaveDuplicates();
        assertThat(new HashSet<>(seen)).isEqualTo(created);
    }

    @Test
    void insertBeforeCursorIsNotCountedAsSkipped_andDeleteBetweenPagesCausesNoDuplicate() throws Exception {
        clock.freezeAt(Instant.parse("2026-10-20T03:00:00Z"));
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        List<UUID> created = createDated(user, project, 6); // ngày 20..25

        PageResult first = page(user, "view=upcoming&from=2026-10-20&limit=3");
        // Chèn TRƯỚC con trỏ: không lọt vào trang 2 và không được tính là "sót" (spec 5.10: giữ trạng thái lúc bắt đầu cuộn).
        UUID inserted = testData.insert(TaskRow.of(user.id(), project, "Mới chèn").dueDate(DAY0));
        UUID deleted = created.get(4);                                          // nằm ở trang 2
        testData.deleteTask(deleted);
        PageResult second = page(user, "view=upcoming&from=2026-10-20&limit=3&cursor=" + first.nextCursor());

        Set<String> all = new HashSet<>(first.ids());
        all.addAll(second.ids());
        assertThat(first.ids().size() + second.ids().size()).isEqualTo(all.size()); // không trùng
        Set<String> expected = new HashSet<>(created.stream().map(UUID::toString).toList());
        expected.remove(deleted.toString());
        assertThat(all).isEqualTo(expected);          // đủ mọi task đã có từ đầu, trừ cái bị xoá; "Mới chèn" không lọt vào
        assertThat(all).doesNotContain(inserted.toString());
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void projectListingPagesByRoots_andCompletedListingPagesDescending() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        List<String> open = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            open.add(testData.insert(TaskRow.of(user.id(), project, "Mở " + i).sortOrder(1024L * (i + 1))).toString());
        }
        List<String> done = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            done.add(0, testData.insert(TaskRow.of(user.id(), project, "Xong " + i)
                    .completedAt(Instant.parse("2026-10-2" + i + "T03:00:00Z"))).toString()); // mới xong trước
        }

        assertThat(walk(user, "projectId=" + project, 2)).containsExactlyElementsOf(open);
        assertThat(walk(user, "projectId=" + project + "&completed=true", 2)).containsExactlyElementsOf(done);
    }

    @Test
    void completedViewPagesNewestFirstAcrossProjects() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        UUID project = testData.project(user.id(), "P");
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID target = i % 2 == 0 ? inbox : project;
            expected.add(0, testData.insert(TaskRow.of(user.id(), target, "Xong " + i)
                    .completedAt(Instant.parse("2026-10-2" + i + "T03:00:00Z"))).toString()); // mới xong trước
        }

        assertThat(walk(user, "view=completed", 2)).containsExactlyElementsOf(expected);
        assertThat(page(user, "view=completed&limit=2").nextCursor()).isNotNull();
    }

    @Test
    void completedViewTiesOnCompletedAt_tieBreakByIdDescendingKeepsPagesDisjoint() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        Set<String> created = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            created.add(testData.insert(TaskRow.of(user.id(), inbox, "Cùng lúc " + i)
                    .completedAt(Instant.parse("2026-10-20T03:00:00Z"))).toString());
        }

        List<String> seen = walk(user, "view=completed", 3);

        assertThat(seen).hasSize(7).doesNotHaveDuplicates();
        assertThat(new HashSet<>(seen)).isEqualTo(created);
        assertThat(seen).isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @Test
    void cursorOfOneProjectIsRejectedForAnotherProject() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = testData.project(user.id(), "A");
        UUID b = testData.project(user.id(), "B");
        for (int i = 0; i < 3; i++) {
            testData.task(user.id(), a, "a" + i);
        }
        String cursor = page(user, "projectId=" + a + "&limit=1").nextCursor();

        authGet(user.accessToken(), "/api/v1/tasks?projectId=" + b + "&cursor=" + cursor)
                .andExpect(status().isBadRequest());
    }

    @Test
    void badParameters_return400() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        String otherViewCursor = CursorCodec.encode("overdue", List.of("a", "b", "c", "d", UUID.randomUUID().toString()));

        authGet(user.accessToken(), "/api/v1/tasks?view=today&limit=0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("limit phải từ 1 đến 200"));
        authGet(user.accessToken(), "/api/v1/tasks?view=today&limit=201").andExpect(status().isBadRequest());
        authGet(user.accessToken(), "/api/v1/tasks?view=today&cursor=rac-khong-hop-le").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Con trỏ phân trang không hợp lệ"));
        authGet(user.accessToken(), "/api/v1/tasks?view=today&cursor=" + otherViewCursor).andExpect(status().isBadRequest());
        authGet(user.accessToken(), "/api/v1/tasks?view=bogus").andExpect(status().isBadRequest());
        authGet(user.accessToken(), "/api/v1/tasks?view=inbox").andExpect(status().isBadRequest()); // bản 3 bỏ view=inbox
        authGet(user.accessToken(), "/api/v1/tasks").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Cần đúng một trong hai tham số view hoặc projectId"));
        authGet(user.accessToken(), "/api/v1/tasks?view=today&projectId=" + inbox).andExpect(status().isBadRequest());
        authGet(user.accessToken(), "/api/v1/tasks?view=upcoming").andExpect(status().isOk()); // from mặc định = hôm nay (3.1)
        authGet(user.accessToken(), "/api/v1/tasks?view=today&from=2026-10-20").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Tham số from và to chỉ dùng với view=upcoming"));
        authGet(user.accessToken(), "/api/v1/tasks?view=overdue&to=2026-10-20").andExpect(status().isBadRequest());
        authGet(user.accessToken(), "/api/v1/tasks?view=today&since=2026-10-01").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Tham số since và until chỉ dùng với view=completed"));
        authGet(user.accessToken(), "/api/v1/tasks?projectId=" + inbox + "&until=2026-10-01").andExpect(status().isBadRequest());
        authGet(user.accessToken(), "/api/v1/tasks?view=completed&since=2026-10-02&until=2026-10-01")
                .andExpect(status().isBadRequest());
        authGet(user.accessToken(), "/api/v1/tasks?view=today&completed=true").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Tham số completed chỉ dùng với projectId"));
        // ngày sai định dạng → 400
        authGet(user.accessToken(), "/api/v1/tasks?view=upcoming&from=khong-phai-ngay").andExpect(status().isBadRequest());
        // đúng kind + đúng số phần nhưng giá trị khoá sai kiểu → 400 (today: due_sort, priority, project, task, id)
        String forged = CursorCodec.encode("today", List.of("khong-phai-gio", "4", "1", "1", UUID.randomUUID().toString()));
        authGet(user.accessToken(), "/api/v1/tasks?view=today&cursor=" + forged).andExpect(status().isBadRequest());
    }
}
