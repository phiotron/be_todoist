package com.taskly.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M2TestData;
import com.taskly.support.M2TestData.TaskSpec;
import com.taskly.support.M8aApiTestBase;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * GET labels/counts và filters/counts: số việc CHƯA xong mỗi nhãn / bộ lọc (cột số bên phải màn Bộ lọc &amp; Nhãn).
 * Đếm như danh sách tasks?labelId= / ?filter= — dự án chưa lưu trữ, trong phạm vi đọc (ReadScope): alice có hai dự án,
 * chỉ MỘT chia sẻ cho bob; carol là người ngoài. Giờ đóng băng 2026-11-04 10:00 (Asia/Ho_Chi_Minh).
 */
class LabelFilterCountsApiTest extends M8aApiTestBase {

    private static final String LABEL_COUNTS = "/api/v1/labels/counts";
    private static final String FILTER_COUNTS = "/api/v1/filters/counts";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    private M2TestData data;
    private RegisteredUser alice;
    private RegisteredUser bob;
    private RegisteredUser carol;
    private UUID shared;
    private UUID secret;

    @BeforeEach
    void fixture() throws Exception {
        clock.freezeAt(Instant.parse("2026-11-04T03:00:00Z"));
        data = new M2TestData(jdbcTemplate, clock);
        alice = newUser();
        bob = newUser();
        carol = newUser();
        shared = createProject(alice, "Dự án chia sẻ");
        secret = createProject(alice, "Dự án riêng");
        addToProject(alice, shared, bob);
    }

    private ResultActions counts(RegisteredUser user, String uri) throws Exception {
        return authGet(user.accessToken(), uri).andExpect(status().isOk());
    }

    private static String labelCount(UUID labelId) {
        return "$[?(@.labelId == '" + labelId + "')].taskCount";
    }

    private static String filterCount(UUID filterId) {
        return "$[?(@.filterId == '" + filterId + "')].taskCount";
    }

    @Test
    void labelCounts_openTasksOfReadableUnarchivedProjects_oneEntryPerLabelInLabelOrder() throws Exception {
        UUID gap = data.label(alice.id(), "gap", 1_000);
        UUID empty = data.label(alice.id(), "trong", 2_000);
        UUID open = data.insert(TaskSpec.of(shared, "mở"));
        data.tag(open, gap);
        data.tag(data.insert(TaskSpec.of(shared, "con").parent(open)), gap);                 // sub-task: có đếm
        data.tag(data.insert(TaskSpec.of(shared, "xong").completedAt(Instant.parse("2026-11-01T00:00:00Z"))), gap);
        data.tag(data.insert(TaskSpec.of(secret, "riêng")), gap);
        UUID archived = createProject(alice, "Cũ");
        data.tag(data.insert(TaskSpec.of(archived, "lưu trữ")), gap);
        data.archiveProject(archived);

        counts(alice, LABEL_COUNTS)
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[*].labelId", contains(gap.toString(), empty.toString())))
                .andExpect(jsonPath(labelCount(gap), contains(3)))
                .andExpect(jsonPath(labelCount(empty), contains(0)));
    }

    @Test
    void labelCounts_collaboratorDoesNotCountTasksOfProjectsNotSharedWithThem() throws Exception {
        UUID sharedTask = data.insert(TaskSpec.of(shared, "Việc chung"));
        UUID secretTask = data.insert(TaskSpec.of(secret, "Việc bí mật"));
        UUID bobs = data.label(bob.id(), "cua-bob");
        UUID carols = data.label(carol.id(), "cua-carol");
        UUID alices = data.label(alice.id(), "cua-alice");
        // Nhãn còn dính trên việc của dự án không (còn) đọc được — không được đếm.
        data.tag(sharedTask, bobs, alices);
        data.tag(secretTask, bobs, carols, alices);

        counts(bob, LABEL_COUNTS).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath(labelCount(bobs), contains(1)));
        counts(carol, LABEL_COUNTS).andExpect(jsonPath(labelCount(carols), contains(0)));
        counts(alice, LABEL_COUNTS).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath(labelCount(alices), contains(2)));
    }

    @Test
    void filterCounts_distinctOpenTasksMatchingAnyPart_inReadScope_brokenQueriesLeftOut() throws Exception {
        data.insert(TaskSpec.of(shared, "S1").dueDate(DAY));
        data.insert(TaskSpec.of(shared, "S2").priority(1));
        data.insert(TaskSpec.of(shared, "S3").dueDate(DAY).completedAt(Instant.parse("2026-11-01T00:00:00Z")));
        data.insert(TaskSpec.of(secret, "P1").dueDate(DAY));
        UUID week = data.filter(alice.id(), "Tuần này", "next 7 days");
        UUID mixed = data.filter(alice.id(), "Gấp hoặc chưa hẹn", "p1, no date"); // S2 khớp cả hai phần: đếm một lần
        UUID none = data.filter(alice.id(), "Không gì", "p2");
        UUID broken = data.filter(alice.id(), "Hỏng", "p1 |");
        UUID bobWeek = data.filter(bob.id(), "Tuần của bob", "next 7 days");
        UUID carolWeek = data.filter(carol.id(), "Tuần của carol", "next 7 days");

        counts(alice, FILTER_COUNTS)
                .andExpect(jsonPath("$[*].filterId", contains(week.toString(), mixed.toString(), none.toString())))
                .andExpect(jsonPath(filterCount(week), contains(2)))
                .andExpect(jsonPath(filterCount(mixed), contains(1)))
                .andExpect(jsonPath(filterCount(none), contains(0)))
                .andExpect(jsonPath(filterCount(broken), hasSize(0)));
        counts(bob, FILTER_COUNTS).andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath(filterCount(bobWeek), contains(1)));
        counts(carol, FILTER_COUNTS).andExpect(jsonPath(filterCount(carolWeek), contains(0)));
    }

    @Test
    void filterCounts_manyFiltersWithDateTerms_eachCountedOnItsOwnParameters() throws Exception {
        data.insert(TaskSpec.of(shared, "hôm nay").dueDate(LocalDate.of(2026, 11, 4)));
        data.insert(TaskSpec.of(shared, "mai").dueDate(LocalDate.of(2026, 11, 5)).priority(2));
        UUID[] today = new UUID[30];
        UUID[] tomorrow = new UUID[30];
        for (int i = 0; i < 30; i++) {
            today[i] = data.filter(alice.id(), "Hôm nay " + i, "date: 04/11");
            tomorrow[i] = data.filter(alice.id(), "Mai " + i, "date after: 04/11 & p2");
        }

        ResultActions result = counts(alice, FILTER_COUNTS).andExpect(jsonPath("$", hasSize(60)));
        for (int i = 0; i < 30; i++) {
            result.andExpect(jsonPath(filterCount(today[i]), contains(1)))
                    .andExpect(jsonPath(filterCount(tomorrow[i]), contains(1)));
        }
    }

    @Test
    void counts_requireAuthentication() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(LABEL_COUNTS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(FILTER_COUNTS))
                .andExpect(status().isUnauthorized());
    }
}
