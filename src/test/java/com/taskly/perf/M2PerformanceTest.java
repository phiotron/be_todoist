package com.taskly.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M2ApiTestBase;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Chạy riêng: .\mvnw test "-Dtest=M2PerformanceTest" "-Dtaskly.perf=true" "-Dgroups=perf".
 * Mỗi danh sách: 1 lần làm nóng + 5 lần đo, lấy trung vị; mục tiêu < 300 ms (spec 5.10).
 */
@Tag("perf")
@EnabledIfSystemProperty(named = "taskly.perf", matches = "true")
class M2PerformanceTest extends M2ApiTestBase {

    private static final int PROJECTS = 40;
    private static final int TASKS_PER_PROJECT = 250;
    private static final int LABELS = 20;
    private static final long LIMIT_MS = 300;

    private UUID seed(UUID userId) {
        UUID first = null;
        for (int i = 1; i <= PROJECTS; i++) {
            UUID project = data.project(userId, "Dự án " + i);
            first = first == null ? project : first;
            jdbcTemplate.update("""
                    insert into tasks (id, workspace_id, project_id, content, description, priority, due_date,
                                       sort_order, version, created_at, updated_at)
                    select gen_random_uuid(), p.workspace_id, p.id,
                           'Việc ' || n || case when n % 10 = 0 then ' họp nhóm' else '' end,
                           case when n % 7 = 0 then 'ghi chú báo cáo' else '' end,
                           1 + n % 4,
                           case when n % 3 = 0 then null else current_date + (n % 90) - 30 end,
                           n * 1024, 0, now(), now()
                    from generate_series(1, ?) as n
                    cross join projects p where p.id = ?
                    """, TASKS_PER_PROJECT, project);
        }
        for (int i = 1; i <= LABELS; i++) {
            data.label(userId, "nhan" + i);
        }
        jdbcTemplate.update("""
                insert into task_labels (task_id, label_id)
                select t.id, l.id
                from (select tt.id, row_number() over (order by tt.id) as rn from tasks tt
                      join projects p on p.id = tt.project_id where p.workspace_id = ?) t
                join (select id, row_number() over (order by id) as rn from labels where user_id = ?) l
                  on l.rn = 1 + t.rn % ?
                """, data.workspaceOf(userId), userId, LABELS);
        jdbcTemplate.execute("ANALYZE");
        return first;
    }

    private void assertFast(String name, String token, String path, String... params) throws Exception {
        apiGet(token, path, params).andExpect(status().isOk());   // làm nóng (plan cache, JIT)
        List<Long> millis = new ArrayList<>();
        for (int run = 0; run < 5; run++) {
            long start = System.nanoTime();
            apiGet(token, path, params).andExpect(status().isOk());
            millis.add((System.nanoTime() - start) / 1_000_000);
        }
        Collections.sort(millis);
        long median = millis.get(millis.size() / 2);
        System.out.println("[perf] " + name + " median=" + median + "ms runs=" + millis);
        assertThat(median).as(name).isLessThan(LIMIT_MS);
    }

    @Test
    void firstPageOfEveryM2ListIsUnder300ms() throws Exception {
        var user = registerUser(uniqueEmail());
        UUID project = seed(user.id());
        String token = user.accessToken();
        UUID label = jdbcTemplate.queryForObject(
                "select id from labels where user_id = ? and name = 'nhan1'", UUID.class, user.id());

        assertFast("search", token, "/api/v1/search", "q", "hop nhom", "limit", "50");
        assertFast("search completed", token, "/api/v1/search", "q", "viec", "completed", "true", "limit", "50");
        assertFast("search objects", token, "/api/v1/search/objects", "q", "du an");
        assertFast("label", token, "/api/v1/tasks", "labelId", label.toString(), "limit", "50");
        assertFast("label group project", token, "/api/v1/tasks", "labelId", label.toString(), "group", "project",
                "sort", "name", "limit", "50");
        assertFast("filter", token, "/api/v1/tasks", "filter", "(p1 | p2) & @nhan1 & !overdue", "limit", "50");
        assertFast("filter ##project", token, "/api/v1/tasks", "filter", "##Dự án 1* & !no date", "limit", "50");
        assertFast("today display", token, "/api/v1/tasks", "view", "today", "sort", "priority", "group", "project",
                "limit", "50");
        assertFast("upcoming display", token, "/api/v1/tasks", "view", "upcoming", "sort", "name", "limit", "50");
        assertFast("project display", token, "/api/v1/tasks", "projectId", project.toString(), "group", "priority",
                "sort", "name", "limit", "50");
    }
}
