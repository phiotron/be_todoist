package com.taskly.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.support.M8aApiTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;

/**
 * PERF (perf2): số câu SQL mỗi endpoint KHÔNG tăng theo số workspace / dự án (mỗi câu ≈ 1.5–2.5 ms round-trip tới
 * Postgres trong docker → N+1 là chi phí chính, không phải thời gian thực thi). Đo bằng proxy DataSource (SqlCounter).
 */
@Import(SqlCounter.class)
class QueryCountTest extends M8aApiTestBase {

    private int count(String token, String method, String path) throws Exception {
        SqlCounter.start();
        if (method.equals("POST")) {
            authPost(token, path, "{}").andExpect(status().isOk());
        } else {
            authGet(token, path).andExpect(status().isOk());
        }
        return SqlCounter.stop().size();
    }

    /** Người dùng có `workspaces` nhóm, mỗi nhóm một dự án có việc quá hạn + comment, cộng 2 dự án cá nhân. */
    private RegisteredUser seed(int workspaces) throws Exception {
        RegisteredUser user = newUser();
        List<UUID> projects = new ArrayList<>(List.of(createProject(user, "CN1"), createProject(user, "CN2")));
        for (int i = 0; i < workspaces; i++) {
            UUID team = createTeam(user, "Nhom" + i);
            projects.add(createTeamProject(user, team, "DA" + i, null));
        }
        for (UUID project : projects) {
            UUID task = createOverdueTask(user, project, "viec");
            authPost(user.accessToken(), "/api/v1/tasks/" + task + "/comments", "{\"content\": \"x\"}")
                    .andExpect(status().isCreated());
        }
        return user;
    }

    @Test
    void projectsAndWorkspacesListsDoNotGrowWithWorkspaceCount() throws Exception {
        RegisteredUser small = seed(2);
        RegisteredUser large = seed(8);
        for (String path : List.of("/api/v1/projects", "/api/v1/workspaces")) {
            count(small.accessToken(), "GET", path); // làm nóng (nạp lớp, cache)
            count(large.accessToken(), "GET", path);
            assertThat(count(large.accessToken(), "GET", path))
                    .as("%s: 8 workspace không được nhiều câu hơn 2 workspace", path)
                    .isEqualTo(count(small.accessToken(), "GET", path));
        }
        // Trần tuyệt đối (đo trước khi sửa: projects = 2 + 2 × workspace, workspaces = 1 + workspace)
        assertThat(count(large.accessToken(), "GET", "/api/v1/projects")).isLessThanOrEqualTo(10);
        assertThat(count(large.accessToken(), "GET", "/api/v1/workspaces")).isLessThanOrEqualTo(2);
    }

    @Test
    void fullSyncStatementCountIsBoundedAndIndependentOfWorkspaceCount() throws Exception {
        RegisteredUser small = seed(2);
        RegisteredUser large = seed(8);
        count(small.accessToken(), "POST", "/api/v1/sync");
        int smallCount = count(small.accessToken(), "POST", "/api/v1/sync");
        int largeCount = count(large.accessToken(), "POST", "/api/v1/sync");
        assertThat(largeCount).as("full sync: 8 workspace không được nhiều câu hơn 2 workspace").isEqualTo(smallCount);
        assertThat(largeCount).as("full sync (đo trước khi sửa: 21 + 4 × workspace)").isLessThanOrEqualTo(20);
    }

    @Test
    void taskListsReuseTheReadScopeInsteadOfRecomputingIt() throws Exception {
        RegisteredUser user = seed(3);
        String token = user.accessToken();
        for (String path : List.of("/api/v1/tasks?view=today", "/api/v1/tasks?view=upcoming")) {
            count(token, "GET", path);
            // user + listScope + trang (đo trước khi sửa: 3 → listing M1 đã đủ gọn; giữ làm chốt chặn hồi quy)
            assertThat(count(token, "GET", path)).as(path).isLessThanOrEqualTo(3);
        }
        // calendar / project dùng listing M2: trước khi sửa tính phạm vi đọc 2 lần (listScope + viewScope)
        assertThat(count(token, "GET", "/api/v1/tasks?view=calendar&from=" + java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC).minusDays(3) + "&to=" + java.time.LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC).plusDays(3)))
                .as("calendar").isLessThanOrEqualTo(4);
    }
}
