package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M2ApiTestBase;
import com.taskly.support.M2TestData.TaskSpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Phân trang con trỏ của search (spec 5.10, 14.1, 18.1 m4): không trùng; task chèn vào phần ĐÃ ĐI QUA thì không hiện
 * (chấp nhận, kéo để làm mới), task chèn sau con trỏ thì hiện; xoá giữa hai trang không gây lặp.
 */
class SearchPagingApiTest extends M2ApiTestBase {

    private static final String SEARCH = "/api/v1/search";

    @Test
    void pagingYieldsSameOrderAsOnePage() throws Exception {
        var user = registerUser(uniqueEmail());
        UUID inbox = data.inboxOf(user.id());
        for (int i = 1; i <= 7; i++) {
            data.insert(TaskSpec.of(inbox, "Việc " + i).dueDate(LocalDate.of(2026, 11, i)));
        }

        List<String> onePage = contents(getOk(user.accessToken(), SEARCH, "q", "viec", "limit", "50"));
        assertThat(onePage).hasSize(7);
        for (int limit = 1; limit <= 3; limit++) {
            assertThat(collectAllContents(user.accessToken(), SEARCH, limit, "q", "viec")).isEqualTo(onePage);
        }
    }

    @Test
    void insertAndDeleteBetweenPages_noDuplicate_insertBeforeCursorIsNotShown() throws Exception {
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        UUID inbox = data.inboxOf(user.id());
        List<UUID> tasks = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            tasks.add(data.insert(TaskSpec.of(inbox, "Việc " + i).dueDate(LocalDate.of(2026, 11, i))));
        }

        String first = getOk(token, SEARCH, "q", "viec", "limit", "3");
        assertThat(contents(first)).containsExactly("Việc 1", "Việc 2", "Việc 3");
        data.insert(TaskSpec.of(inbox, "Việc 0").dueDate(LocalDate.of(2026, 10, 31)));  // trước con trỏ → không hiện
        data.insert(TaskSpec.of(inbox, "Việc 9").dueDate(LocalDate.of(2026, 11, 9)));   // sau con trỏ → hiện
        data.deleteTask(tasks.get(3));                                                   // "Việc 4" chưa xem → mất

        String second = getOk(token, SEARCH, "q", "viec", "limit", "3", "cursor", nextCursor(first));
        assertThat(contents(second)).containsExactly("Việc 5", "Việc 6", "Việc 9");
        assertThat(nextCursor(second)).isNull();
    }

    @Test
    void completedSearchPages_newestFirst() throws Exception {
        var user = registerUser(uniqueEmail());
        UUID inbox = data.inboxOf(user.id());
        for (int i = 1; i <= 5; i++) {
            data.insert(TaskSpec.of(inbox, "Xong " + i).completedAt(Instant.parse("2026-10-0" + i + "T00:00:00Z")));
        }

        assertThat(collectAllContents(user.accessToken(), SEARCH, 2, "q", "xong", "completed", "true"))
                .containsExactly("Xong 5", "Xong 4", "Xong 3", "Xong 2", "Xong 1");
    }

    @Test
    void badCursorOrLimit_is400() throws Exception {
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        UUID inbox = data.inboxOf(user.id());
        data.task(inbox, "Việc 1");
        data.task(inbox, "Việc 2");
        data.insert(TaskSpec.of(inbox, "Việc xong").completedAt(Instant.parse("2026-10-01T00:00:00Z")));
        String openCursor = nextCursor(getOk(token, SEARCH, "q", "viec", "limit", "1"));

        apiGet(token, SEARCH, "q", "viec", "cursor", "%%%hỏng%%%").andExpect(status().isBadRequest());
        apiGet(token, SEARCH, "q", "viec", "completed", "true", "cursor", openCursor).andExpect(status().isBadRequest());
        apiGet(token, SEARCH, "q", "khac", "cursor", openCursor).andExpect(status().isBadRequest());
        apiGet(token, SEARCH, "q", "viec", "limit", "0").andExpect(status().isBadRequest());
        apiGet(token, SEARCH, "q", "viec", "limit", "201").andExpect(status().isBadRequest());
        apiGet(token, SEARCH, "q", "viec", "limit", "200").andExpect(status().isOk());
    }

    /** Mọi khoá (nhóm khớp tên, similarity, due có / không, id) đều đi qua con trỏ; không null → không NPE, không lặp. */
    @Test
    void pagingAcrossRankGroupsAndNullDue_sameOrderAsOnePage() throws Exception {
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        UUID inbox = data.inboxOf(user.id());
        data.insert(TaskSpec.of(inbox, "Báo cáo").dueDate(LocalDate.of(2026, 11, 1)));
        data.insert(TaskSpec.of(inbox, "Báo cáo"));
        data.insert(TaskSpec.of(inbox, "Báo cáo"));
        data.insert(TaskSpec.of(inbox, "Báo cáo tuần dài dòng quá").dueDatetime(LocalDateTime.of(2026, 11, 1, 9, 0)));
        data.insert(TaskSpec.of(inbox, "Việc khác").description("nhớ báo cáo").dueDate(LocalDate.of(2026, 10, 30)));
        data.insert(TaskSpec.of(inbox, "Việc khác").description("nhớ báo cáo"));
        data.insert(TaskSpec.of(inbox, "Việc khác").description("báo cáo"));

        List<String> onePage = contents(getOk(token, SEARCH, "q", "bao cao", "limit", "50"));
        assertThat(onePage).hasSize(7);
        assertThat(onePage.subList(0, 4)).allMatch(c -> c.startsWith("Báo cáo"));
        assertThat(onePage.subList(4, 7)).containsOnly("Việc khác");
        List<String> ids = ids(getOk(token, SEARCH, "q", "bao cao", "limit", "50"));
        for (int limit = 1; limit <= 4; limit++) {
            assertThat(collectAllContents(token, SEARCH, limit, "q", "bao cao")).isEqualTo(onePage);
            assertThat(collectAllIds(token, limit)).isEqualTo(ids);
        }
    }

    private List<String> collectAllIds(String token, int limit) throws Exception {
        List<String> all = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 50; page++) {
            String json = cursor == null
                    ? getOk(token, SEARCH, "q", "bao cao", "limit", String.valueOf(limit))
                    : getOk(token, SEARCH, "q", "bao cao", "limit", String.valueOf(limit), "cursor", cursor);
            all.addAll(ids(json));
            cursor = nextCursor(json);
            if (cursor == null) {
                return all;
            }
        }
        throw new AssertionError("con trỏ không tiến");
    }
}
