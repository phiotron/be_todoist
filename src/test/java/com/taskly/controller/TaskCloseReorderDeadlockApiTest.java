package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.support.LockWaits;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Deadlock cấp HÀNG task: close (ghi hàng cha + con cháu qua entity, thứ tự do Hibernate / truy vấn) × reorder task
 * (khoá P rồi khoá hàng anh em theo id TĂNG DẦN rồi native UPDATE). Nếu close không giữ P và ghi hàng theo thứ tự khác
 * thì hai giao dịch chờ vòng (Postgres: "deadlock detected" → 409 / 500). Test xác định hai kết nối + hai request:
 * kết nối H giữ hàng GIỮA (id Java-giữa), close vào trước (giữ hàng id lớn, chờ hàng giữa), reorder vào sau (giữ hàng id
 * nhỏ, chờ hàng giữa), H nhả → vòng chờ nếu hai bên khoá ngược thứ tự. Cả hai request đều phải 200.
 */
class TaskCloseReorderDeadlockApiTest extends IntegrationTestBase {

    @Autowired
    private DataSource dataSource;

    private static UUID randomWithSign(boolean negativeMsb) {
        while (true) {
            UUID id = UUID.randomUUID();
            if ((id.getMostSignificantBits() < 0) == negativeMsb) {
                return id;
            }
        }
    }

    /** ba id sắp theo UUID.compareTo; loOpposite: id nhỏ nhất có msb âm (khác dấu hai id kia → PG so khác Java). */
    private static List<UUID> ids(boolean loOpposite) {
        List<UUID> ids = new ArrayList<>(List.of(randomWithSign(loOpposite), randomWithSign(false), randomWithSign(false)));
        Collections.sort(ids);
        return ids;
    }

    private void insertTask(UUID id, UUID user, UUID project, UUID parent, String content, long sortOrder) {
        jdbcTemplate.update("insert into tasks (id, workspace_id, project_id, parent_id, creator_id, content, priority, "
                        + "sort_order, created_at, updated_at) values (?, (select workspace_id from projects where id = ?), "
                        + "?, ?, ?, ?, 1, ?, now(), now())", id, project, project, parent, user, content, sortOrder);
    }

    private String respond(org.springframework.test.web.servlet.ResultActions actions) throws Exception {
        var response = actions.andReturn().getResponse();
        return response.getStatus() + " " + response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void scenario(List<UUID> ascending, boolean insertDescending, boolean viaBatch) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID parent = testData.task(user.id(), project, "Cha");
        UUID lo = ascending.get(0);
        UUID mid = ascending.get(1);
        UUID hi = ascending.get(2);
        List<UUID> order = new ArrayList<>(ascending);
        if (insertDescending) {
            Collections.reverse(order);
        }
        long sort = 1;
        // sort_order liền nhau (1, 2, 3): đặt hi sau lo buộc đánh số lại → reorder khoá cả ba hàng
        for (UUID id : order) {
            long so = id.equals(lo) ? 1 : id.equals(mid) ? 2 : 3;
            insertTask(id, user.id(), project, parent, "C" + (sort++), so);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection held = dataSource.getConnection();
        held.setAutoCommit(false);
        try (PreparedStatement ps = held.prepareStatement("select id from tasks where id = ? for no key update")) {
            ps.setObject(1, mid);
            ps.executeQuery().close();
        }
        try {
            int holder = LockWaits.backendPid(held);
            Future<String> close = pool.submit(() -> viaBatch
                    ? respond(authPost(user.accessToken(), "/api/v1/tasks/batch",
                            "{\"action\": \"CLOSE\", \"taskIds\": [\"%s\"]}".formatted(parent)))
                    : respond(authPost(user.accessToken(), "/api/v1/tasks/" + parent + "/close")));
            LockWaits.awaitBlockedBy(jdbcTemplate, holder, 1);
            Future<String> reorder = pool.submit(() -> respond(authPost(user.accessToken(), "/api/v1/reorder",
                    "{\"type\": \"TASK\", \"id\": \"%s\", \"afterId\": \"%s\"}".formatted(hi, lo))));
            LockWaits.awaitBlockedBy(jdbcTemplate, holder, 2);
            held.rollback();
            held.close();
            held = null;
            assertThat(close.get(30, TimeUnit.SECONDS)).as("close").startsWith("200 ");
            assertThat(reorder.get(30, TimeUnit.SECONDS)).as("reorder").startsWith("200 ");
        } finally {
            if (held != null) {
                held.rollback();
                held.close();
            }
            pool.shutdownNow();
        }
    }

    @Test
    void closeAndReorder_sameSignIds_insertedDescending_doNotDeadlock() throws Exception {
        scenario(ids(false), true, false);
    }

    @Test
    void closeAndReorder_sameSignIds_insertedAscending_doNotDeadlock() throws Exception {
        scenario(ids(false), false, false);
    }

    @Test
    void closeAndReorder_mixedSignIds_insertedDescending_doNotDeadlock() throws Exception {
        scenario(ids(true), true, false);
    }

    @Test
    void closeAndReorder_mixedSignIds_insertedAscending_doNotDeadlock() throws Exception {
        scenario(ids(true), false, false);
    }

    @Test
    void batchCloseAndReorder_insertedDescending_doNotDeadlock() throws Exception {
        scenario(ids(false), true, true);
    }

    @Test
    void batchCloseAndReorder_mixedSignIds_insertedDescending_doNotDeadlock() throws Exception {
        scenario(ids(true), true, true);
    }
}
