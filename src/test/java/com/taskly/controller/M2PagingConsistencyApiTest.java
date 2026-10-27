package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.support.M2ApiTestBase;
import com.taskly.support.M2TestData.TaskSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Keyset (spec 5.10, 18.1 m4) không trùng khi chèn / xoá / đổi khoá của task CHƯA xem giữa hai lần tải, kể cả khi Display
 * nhóm + sắp (con trỏ mang đủ khoá nhóm + khoá sắp); task chèn vào phần ĐÃ ĐI QUA không hiện (chấp nhận, kéo để làm mới).
 */
class M2PagingConsistencyApiTest extends M2ApiTestBase {

    private static final String TASKS = "/api/v1/tasks";

    private static String[] with(String[] base, String... more) {
        List<String> all = new ArrayList<>(List.of(base));
        all.addAll(List.of(more));
        return all.toArray(String[]::new);
    }

    /** Đi tiếp từ con trỏ tới hết; mọi trang tôn trọng limit (task gốc ở view dự án có thể kèm cây con). */
    private List<String> restFrom(String token, String cursor, int limit, String... base) throws Exception {
        List<String> rest = new ArrayList<>();
        String next = cursor;
        for (int page = 0; page < 20 && next != null; page++) {
            String json = getOk(token, TASKS, with(base, "limit", String.valueOf(limit), "cursor", next));
            rest.addAll(contents(json));
            next = nextCursor(json);
        }
        assertThat(next).as("con trỏ phải kết thúc").isNull();
        return rest;
    }

    @Test
    void groupedLabelListing_changesBetweenPages_noDuplicateNoMiss() throws Exception {
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        UUID inbox = data.inboxOf(user.id());
        UUID home = data.label(user.id(), "nha");
        Map<String, UUID> ids = new HashMap<>();
        for (int priority = 1; priority <= 3; priority++) {
            for (String letter : List.of("a", "b", "c")) {
                String name = letter + priority;
                UUID id = data.insert(TaskSpec.of(inbox, name).priority(priority));
                data.tag(id, home);
                ids.put(name, id);
            }
        }
        String[] base = {"labelId", home.toString(), "group", "priority", "sort", "name"};

        String first = getOk(token, TASKS, with(base, "limit", "3"));
        assertThat(contents(first)).containsExactly("a1", "b1", "c1");

        data.deleteTask(ids.get("b2"));                                                       // chưa xem → mất
        jdbcTemplate.update("update tasks set content = 'zz2' where id = ?", ids.get("c2"));  // chưa xem, đổi khoá → 1 lần ở chỗ mới
        data.tag(data.insert(TaskSpec.of(inbox, "d2").priority(2)), home);                    // sau con trỏ → hiện
        data.tag(data.insert(TaskSpec.of(inbox, "aa1").priority(1)), home);                   // trước con trỏ → không hiện

        List<String> rest = restFrom(token, nextCursor(first), 3, base);
        assertThat(rest).containsExactly("a2", "d2", "zz2", "a3", "b3", "c3");
        assertThat(rest).doesNotContainAnyElementsOf(List.of("a1", "b1", "c1", "aa1", "b2", "c2"));
    }

    @Test
    void projectListing_rootsWithSubtrees_whenRootsAreDeletedAndAdded() throws Exception {
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        UUID work = data.project(user.id(), "Work");
        Map<String, UUID> roots = new HashMap<>();
        for (int i = 1; i <= 4; i++) {
            UUID root = data.insert(TaskSpec.of(work, "r" + i).sortOrder(i * 1_000L));
            data.insert(TaskSpec.of(work, "c" + i).parent(root).sortOrder(1_000));
            roots.put("r" + i, root);
        }
        String[] base = {"projectId", work.toString(), "sort", "manual"};

        String first = getOk(token, TASKS, with(base, "limit", "2"));
        assertThat(contents(first)).containsExactly("r1", "c1", "r2", "c2");

        data.deleteTask(roots.get("r3"));                                         // cây con c3 mất theo (cascade)
        data.insert(TaskSpec.of(work, "r5").sortOrder(5_000));                    // sau con trỏ → hiện
        data.insert(TaskSpec.of(work, "r0").sortOrder(500));                      // trước con trỏ → không hiện

        assertThat(restFrom(token, nextCursor(first), 2, base)).containsExactly("r4", "c4", "r5");
    }

    @Test
    void filterListing_sortedByAddedDescending_whenTasksAreAddedAndDeleted() throws Exception {
        var user = registerUser(uniqueEmail());
        String token = user.accessToken();
        UUID inbox = data.inboxOf(user.id());
        Map<String, UUID> ids = new HashMap<>();
        for (int day = 1; day <= 6; day++) {
            ids.put("c" + day, data.insert(TaskSpec.of(inbox, "c" + day)
                    .createdAt(Instant.parse("2026-10-0" + day + "T00:00:00Z"))));
        }
        String[] base = {"filter", "no date", "sort", "added", "order", "desc"};

        String first = getOk(token, TASKS, with(base, "limit", "2"));
        assertThat(contents(first)).containsExactly("c6", "c5");

        data.deleteTask(ids.get("c3"));
        data.insert(TaskSpec.of(inbox, "new").createdAt(Instant.parse("2026-10-10T00:00:00Z")));   // mới nhất, trước con trỏ
        data.insert(TaskSpec.of(inbox, "mid").createdAt(Instant.parse("2026-10-02T12:00:00Z")));

        assertThat(restFrom(token, nextCursor(first), 2, base)).containsExactly("c4", "mid", "c2", "c1");
    }
}
