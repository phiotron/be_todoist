package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.common.error.ProblemTexts;
import com.taskly.support.M2ApiTestBase;
import com.taskly.support.M2TestData.TaskSpec;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** GET /api/v1/search (spec 12.6, UC 8, BR-09). */
class SearchApiTest extends M2ApiTestBase {

    private static final String SEARCH = "/api/v1/search";

    private RegisteredUser user;
    private String token;
    private UUID inbox;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser(uniqueEmail());
        token = user.accessToken();
        inbox = data.inboxOf(user.id());
    }

    private UUID task(String content) {
        return data.task(inbox, content);
    }

    private List<String> found(String q) throws Exception {
        return contents(getOk(token, SEARCH, "q", q));
    }

    @Test
    void matchesIgnoringCaseAndVietnameseAccents() throws Exception {
        task("Họp nhóm sáng");
        task("Đi chợ");
        task("ĐỌC SÁCH");
        task("Không liên quan");

        assertThat(found("hop")).containsExactly("Họp nhóm sáng");
        assertThat(found("HỌP")).containsExactly("Họp nhóm sáng");
        assertThat(found("di cho")).containsExactly("Đi chợ");
        assertThat(found("đọc")).containsExactly("ĐỌC SÁCH");
    }

    @Test
    void matchesDescription_butContentMatchesRankFirst() throws Exception {
        data.insert(TaskSpec.of(inbox, "Việc khác").description("Nhớ viết báo cáo"));
        task("Báo cáo tuần");

        assertThat(found("bao cao")).containsExactly("Báo cáo tuần", "Việc khác");
    }

    @Test
    void everyTermMustAppearInContentOrDescription() throws Exception {
        task("Họp nhóm dự án");
        task("Họp khách");
        data.insert(TaskSpec.of(inbox, "Gọi điện").description("cho khách hàng"));

        assertThat(found("hop du an")).containsExactly("Họp nhóm dự án");
        assertThat(found("goi khach")).containsExactly("Gọi điện");
    }

    @Test
    void starIsWildcard_percentUnderscoreAndBackslashAreLiteral() throws Exception {
        task("Hiệp hội");
        task("Họp");
        task("Giảm 50%");
        task("Giảm 500 việc");
        task("file_a");
        task("fileXa");
        task("C:\\tmp");

        assertThat(found("h*p")).containsExactlyInAnyOrder("Hiệp hội", "Họp");
        assertThat(found("50%")).containsExactly("Giảm 50%");
        assertThat(found("file_a")).containsExactly("file_a");
        assertThat(found("c:\\tmp")).containsExactly("C:\\tmp");
    }

    @Test
    void closerMatchesComeFirst_thenEarlierDue_noDueLast() throws Exception {
        task("Họp nhóm dự án rất dài về kế hoạch năm sau");
        task("Họp");
        assertThat(found("họp")).containsExactly("Họp", "Họp nhóm dự án rất dài về kế hoạch năm sau");

        data.insert(TaskSpec.of(inbox, "Mua sữa").dueDate(LocalDate.of(2026, 11, 10)));
        data.insert(TaskSpec.of(inbox, "Mua sữa"));
        UUID early = data.insert(TaskSpec.of(inbox, "Mua sữa").dueDate(LocalDate.of(2026, 11, 5)));
        String json = getOk(token, SEARCH, "q", "mua sua");
        assertThat(ids(json).get(0)).isEqualTo(early.toString());
        assertThat(com.jayway.jsonpath.JsonPath.<List<String>>read(json, "$.items[*].dueDate"))
                .containsExactly("2026-11-05", "2026-11-10", null);
    }

    @Test
    void completedTasks_onlyWithFlag_newestFirst() throws Exception {
        task("Việc đang mở");
        data.insert(TaskSpec.of(inbox, "Việc xong cũ").completedAt(Instant.parse("2026-10-01T00:00:00Z")));
        data.insert(TaskSpec.of(inbox, "Việc xong mới").completedAt(Instant.parse("2026-10-20T00:00:00Z")));

        assertThat(found("viec")).containsExactly("Việc đang mở");
        assertThat(contents(getOk(token, SEARCH, "q", "viec", "completed", "true")))
                .containsExactly("Việc xong mới", "Việc xong cũ");
    }

    /** Spec 12.6, 18.1 m5: loại dự án lưu trữ; task (đã xong) của section lưu trữ chỉ hiện ở completed=true. */
    @Test
    void excludesArchivedProjects_archivedSectionTasksAppearOnlyInCompletedMode() throws Exception {
        UUID archivedProject = data.project(user.id(), "Cũ");
        data.task(archivedProject, "Việc mở trong dự án lưu trữ");
        data.insert(TaskSpec.of(archivedProject, "Việc xong trong dự án lưu trữ")
                .completedAt(Instant.parse("2026-10-01T00:00:00Z")));
        data.archiveProject(archivedProject);
        UUID active = data.project(user.id(), "Mới");
        UUID oldSection = data.section(active, "Section cũ");
        data.insert(TaskSpec.of(active, "Việc trong section lưu trữ").section(oldSection)
                .completedAt(Instant.parse("2026-10-02T00:00:00Z")));   // section lưu trữ → mọi task trong đó đã xong
        data.archiveSection(oldSection);
        data.task(active, "Việc thường");

        assertThat(found("viec")).containsExactly("Việc thường");
        assertThat(contents(getOk(token, SEARCH, "q", "viec", "completed", "true")))
                .containsExactly("Việc trong section lưu trữ");
    }

    @Test
    void onlySeesOwnTasks() throws Exception {
        var other = registerUser(uniqueEmail());
        data.task(data.inboxOf(other.id()), "Họp bí mật");
        task("Họp công khai");

        assertThat(found("hop")).containsExactly("Họp công khai");
    }

    @Test
    void emptyQueryReturnsNothing_tooLongIs400() throws Exception {
        task("Họp");

        apiGet(token, SEARCH, "q", "   ").andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
        apiGet(token, SEARCH).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        apiGet(token, SEARCH, "q", "a".repeat(101)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(ProblemTexts.SEARCH_QUERY_TOO_LONG));
        apiGet(token, SEARCH, "q", "a".repeat(100)).andExpect(status().isOk());
    }

    @Test
    void resultsCarryProjectNameAndParentContent() throws Exception {
        UUID parent = task("Chuẩn bị họp");
        data.insert(TaskSpec.of(inbox, "Đặt phòng họp").parent(parent));

        apiGet(token, SEARCH, "q", "dat phong")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].projectName").value("Inbox"))
                .andExpect(jsonPath("$.items[0].parentContent").value("Chuẩn bị họp"));
    }
}
