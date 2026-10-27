package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M2ApiTestBase;
import com.taskly.support.M2TestData.TaskSpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Mỗi từ khoá filter của spec 12.7 chạy trên Postgres thật. Giờ đóng băng 2026-11-04T03:00:00Z = 10:00 thứ Tư
 * 04/11 ở Asia/Ho_Chi_Minh. Bộ dữ liệu cố định (mọi task đang mở trừ khi ghi khác):
 *
 * T-today d04 P4 Inbox · T-tomorrow d05 P3 Work/Meetings @gap · T-yesterday d03 P1 Work @nha ·
 * T-nodate P2 Home · T-timed-past 04/11 08:00 Inbox · T-timed-future 04/11 18:30 Work/Docs ·
 * T-deadline Home deadline 10/11 · T-sub (con của T-tomorrow) d06 Work/Meetings · T-subproj d12 dự án Sub (con của Work) ·
 * T-done đã xong · Báo cáo tuần (mô tả "Gửi sếp") d20 Home @nha · T-far 03/12 Inbox · T-old (tạo 01/10) Inbox ·
 * T-rd dự án "R&D" · T-ab dự án "A, B" · T-arch dự án lưu trữ d04.
 */
class FilterKeywordsApiTest extends M2ApiTestBase {

    private static final String TASKS = "/api/v1/tasks";

    private String token;

    private static LocalDate d(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    @BeforeEach
    void dataset() throws Exception {
        clock.freezeAt(Instant.parse("2026-11-04T03:00:00Z"));
        RegisteredUser user = registerUser(uniqueEmail());
        token = user.accessToken();
        UUID inbox = data.inboxOf(user.id());
        UUID work = data.project(user.id(), "Work");
        UUID sub = data.project(user.id(), work, "Sub");
        UUID home = data.project(user.id(), "Home");
        UUID meetings = data.section(work, "Meetings");
        UUID docs = data.section(work, "Docs");
        UUID gap = data.label(user.id(), "gap");
        UUID nha = data.label(user.id(), "nha");

        data.insert(TaskSpec.of(inbox, "T-today").dueDate(d(11, 4)));
        UUID tomorrow = data.insert(TaskSpec.of(work, "T-tomorrow").section(meetings).dueDate(d(11, 5)).priority(3));
        data.tag(tomorrow, gap);
        data.tag(data.insert(TaskSpec.of(work, "T-yesterday").dueDate(d(11, 3)).priority(1)), nha);
        data.insert(TaskSpec.of(home, "T-nodate").priority(2));
        data.insert(TaskSpec.of(inbox, "T-timed-past").dueDatetime(LocalDateTime.of(2026, 11, 4, 8, 0)));
        data.insert(TaskSpec.of(work, "T-timed-future").section(docs).dueDatetime(LocalDateTime.of(2026, 11, 4, 18, 30)));
        data.insert(TaskSpec.of(home, "T-deadline").deadline(d(11, 10)));
        data.insert(TaskSpec.of(work, "T-sub").section(meetings).parent(tomorrow).dueDate(d(11, 6)));
        data.insert(TaskSpec.of(sub, "T-subproj").dueDate(d(11, 12)));
        data.insert(TaskSpec.of(inbox, "T-done").dueDate(d(11, 4)).completedAt(Instant.parse("2026-11-01T00:00:00Z")));
        data.tag(data.insert(TaskSpec.of(home, "Báo cáo tuần").description("Gửi sếp").dueDate(d(11, 20))), nha);
        data.insert(TaskSpec.of(inbox, "T-far").dueDate(d(12, 3)));
        data.insert(TaskSpec.of(inbox, "T-old").createdAt(Instant.parse("2026-10-01T03:00:00Z")));
        data.insert(TaskSpec.of(data.project(user.id(), "R&D"), "T-rd"));
        data.insert(TaskSpec.of(data.project(user.id(), "A, B"), "T-ab"));
        UUID archived = data.project(user.id(), "Archived");
        data.insert(TaskSpec.of(archived, "T-arch").dueDate(d(11, 4)));
        data.archiveProject(archived);
        UUID other = registerUser(uniqueEmail()).id();
        data.insert(TaskSpec.of(data.inboxOf(other), "Của người khác").dueDate(d(11, 4)));
    }

    private List<String> q(String query) throws Exception {
        return contents(getOk(token, TASKS, "filter", query, "limit", "200"));
    }

    @Test
    void dates() throws Exception {
        assertThat(q("today")).containsExactlyInAnyOrder("T-today", "T-timed-past", "T-timed-future");
        assertThat(q("tomorrow")).containsExactly("T-tomorrow");
        assertThat(q("yesterday")).containsExactly("T-yesterday");
        // overdue / od = isOverdue (spec 5.1): T-timed-past (08:00 < 10:00 hôm nay) tính là quá hạn dù ở khối Hôm nay
        assertThat(q("overdue")).containsExactlyInAnyOrder("T-yesterday", "T-timed-past");
        assertThat(q("od")).containsExactlyInAnyOrder("T-yesterday", "T-timed-past");
        assertThat(q("no date")).containsExactlyInAnyOrder("T-nodate", "T-deadline", "T-old", "T-rd", "T-ab");
        assertThat(q("no time")).containsExactlyInAnyOrder("T-today", "T-tomorrow", "T-yesterday", "T-sub",
                "T-subproj", "Báo cáo tuần", "T-far");
        assertThat(q("date: 05/11")).containsExactly("T-tomorrow");
        assertThat(q("date: 2026-11-05")).containsExactly("T-tomorrow");
        assertThat(q("05/11")).containsExactly("T-tomorrow");
        assertThat(q("date before: 05/11")).containsExactlyInAnyOrder("T-today", "T-yesterday", "T-timed-past",
                "T-timed-future");
        assertThat(q("date after: 11/11")).containsExactlyInAnyOrder("T-subproj", "Báo cáo tuần", "T-far");
        assertThat(q("next 3 days")).containsExactlyInAnyOrder("T-today", "T-timed-past", "T-timed-future",
                "T-tomorrow", "T-sub");
        assertThat(q("3 days")).hasSize(5);
    }

    /** Spec 5.2: ngày giờ floating yyyy-MM-ddTHH:mm[:ss], so theo phút (múi giờ tài khoản chỉ dùng cho created). */
    @Test
    void dateTimeLiterals_useFloatingMinutes() throws Exception {
        assertThat(q("date: 2026-11-04T08:00")).containsExactly("T-timed-past");
        assertThat(q("date: 2026-11-04T08:00:59")).containsExactly("T-timed-past");        // giây bị cắt về phút
        assertThat(q("2026-11-04T18:30")).containsExactly("T-timed-future");
        assertThat(q("date before: 2026-11-04T12:00")).containsExactlyInAnyOrder("T-yesterday", "T-timed-past");
        assertThat(q("date after: 2026-11-04T12:00")).containsExactlyInAnyOrder("T-timed-future", "T-tomorrow",
                "T-sub", "T-subproj", "Báo cáo tuần", "T-far");
        assertThat(q("created: 2026-10-01T10:00")).containsExactly("T-old");          // 03:00Z = 10:00 Asia/Ho_Chi_Minh
        assertThat(q("created before: 2026-10-01T10:01")).containsExactly("T-old");
        assertThat(q("created after: 2026-10-01T10:00")).doesNotContain("T-old").contains("T-today");
        apiGet(token, TASKS, "filter", "deadline: 2026-11-10T09:00").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("invalid-date"));    // deadline chỉ có ngày
    }

    @Test
    void vietnameseAliasesMatchTheEnglishKeywords() throws Exception {
        assertThat(q("hôm nay")).containsExactlyInAnyOrderElementsOf(q("today"));
        assertThat(q("ngày mai")).containsExactlyElementsOf(q("tomorrow"));
        assertThat(q("hôm qua")).containsExactlyElementsOf(q("yesterday"));
        assertThat(q("quá hạn")).containsExactlyInAnyOrderElementsOf(q("overdue"));
        assertThat(q("không có ngày")).containsExactlyInAnyOrderElementsOf(q("no date"));
    }

    @Test
    void deadlinesAndPriorities() throws Exception {
        assertThat(q("deadline: 10/11")).containsExactly("T-deadline");
        assertThat(q("deadline before: 11/11")).containsExactly("T-deadline");
        assertThat(q("deadline after: 09/11")).containsExactly("T-deadline");
        assertThat(q("deadline after: 10/11")).isEmpty();
        assertThat(q("no deadline")).doesNotContain("T-deadline").contains("T-today");
        assertThat(q("p1")).containsExactly("T-yesterday");
        assertThat(q("p2")).containsExactly("T-nodate");
        assertThat(q("p3")).containsExactly("T-tomorrow");
        assertThat(q("p4 & #Inbox")).containsExactlyInAnyOrder("T-today", "T-timed-past", "T-far", "T-old");
        assertThat(q("no priority & #Inbox")).containsExactlyInAnyOrder("T-today", "T-timed-past", "T-far", "T-old");
    }

    @Test
    void projectsAndSections() throws Exception {
        assertThat(q("#Work")).containsExactlyInAnyOrder("T-tomorrow", "T-yesterday", "T-timed-future", "T-sub");
        assertThat(q("##Work")).containsExactlyInAnyOrder("T-tomorrow", "T-yesterday", "T-timed-future", "T-sub",
                "T-subproj");
        assertThat(q("#work")).hasSize(4);
        assertThat(q("#Wor*")).hasSize(4);
        assertThat(q("#Sub")).containsExactly("T-subproj");
        assertThat(q("/Meetings")).containsExactlyInAnyOrder("T-tomorrow", "T-sub");
        assertThat(q("/*")).containsExactlyInAnyOrder("T-tomorrow", "T-timed-future", "T-sub");
        assertThat(q("!/*")).doesNotContain("T-tomorrow", "T-timed-future", "T-sub").contains("T-today", "T-yesterday");
        assertThat(q("#Work & /Meetings")).containsExactlyInAnyOrder("T-tomorrow", "T-sub");
        assertThat(q("#Work & !/*")).containsExactly("T-yesterday");
        assertThat(q("!/Meetings & #Work")).containsExactlyInAnyOrder("T-yesterday", "T-timed-future");
    }

    @Test
    void escapedCharactersInNames() throws Exception {
        assertThat(q("#R\\&D")).containsExactly("T-rd");
        assertThat(q("#A\\, B")).containsExactly("T-ab");
        assertThat(q("#R*")).containsExactly("T-rd");
    }

    @Test
    void labels() throws Exception {
        assertThat(q("@nha")).containsExactlyInAnyOrder("T-yesterday", "Báo cáo tuần");
        assertThat(q("%nha")).containsExactlyInAnyOrder("T-yesterday", "Báo cáo tuần");
        assertThat(q("@n*")).containsExactlyInAnyOrder("T-yesterday", "Báo cáo tuần");
        assertThat(q("@gap")).containsExactly("T-tomorrow");
        assertThat(q("no labels")).doesNotContain("T-yesterday", "T-tomorrow", "Báo cáo tuần").contains("T-today");
    }

    @Test
    void searchCreatedSubtaskRecurring() throws Exception {
        assertThat(q("search: báo")).containsExactly("Báo cáo tuần");
        assertThat(q("search: bao cao")).containsExactly("Báo cáo tuần");
        assertThat(q("search: sếp")).containsExactly("Báo cáo tuần");
        assertThat(q("search: t-to")).containsExactlyInAnyOrder("T-today", "T-tomorrow");
        assertThat(q("created: 01/10")).containsExactly("T-old");
        assertThat(q("created before: today")).containsExactly("T-old");
        assertThat(q("created: today")).doesNotContain("T-old").contains("T-today");
        assertThat(q("subtask")).containsExactly("T-sub");
        assertThat(q("!subtask & #Work & /Meetings")).containsExactly("T-tomorrow");
        assertThat(q("recurring")).isEmpty();
    }

    @Test
    void operatorsAndPrecedence() throws Exception {
        assertThat(q("p1 | p2")).containsExactlyInAnyOrder("T-yesterday", "T-nodate");
        assertThat(q("(p1 | p2) & !@gap")).containsExactlyInAnyOrder("T-yesterday", "T-nodate");
        assertThat(q("p1 | p3 & @gap")).containsExactlyInAnyOrder("T-yesterday", "T-tomorrow");   // & chặt hơn |
        assertThat(q("(p1 | p3) & @gap")).containsExactly("T-tomorrow");
        assertThat(q("today & !overdue")).containsExactlyInAnyOrder("T-today", "T-timed-future");
        assertThat(q("!(today | overdue) & no date"))
                .containsExactlyInAnyOrder("T-nodate", "T-deadline", "T-old", "T-rd", "T-ab");
        assertThat(q("!!p1")).containsExactly("T-yesterday");
    }

    @Test
    void resultsNeverIncludeCompletedArchivedOrForeignTasks() throws Exception {
        assertThat(q("today")).doesNotContain("T-done", "T-arch", "Của người khác");
        assertThat(q("search: t-")).doesNotContain("T-done", "T-arch");
    }

    @Test
    void unsupportedAssignedToIs400() throws Exception {
        apiGet(token, TASKS, "filter", "assigned to: me").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("filter-syntax"))
                .andExpect(jsonPath("$.reason").value("unsupported"));
    }

    @Test
    void sqlInjectionAttemptsAreTreatedAsPlainNames() throws Exception {
        assertThat(q("#x'; DROP TABLE tasks; --")).isEmpty();
        assertThat(q("search: ' OR 1=1 --")).isEmpty();
        assertThat(q("@a%' OR '1'='1")).isEmpty();
        assertThat(q("today")).isNotEmpty();
    }
}
