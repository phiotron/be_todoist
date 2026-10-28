package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.support.LockWaits;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Deadlock cấp HÀNG task giữa reorder (đánh số lại các con, khoá hàng theo id tăng dần) và từng thao tác khác ghi hàng
 * cha + con cháu: close, reopen(undo), xoá (cascade), move (relocate cây con), cùng các dạng tasks/batch. (Duplicate
 * chỉ INSERT hàng mới, không ghi hàng đang có nên không vào ma trận.)
 * Hai kết nối + hai request theo thứ tự ngược nhau: kết nối H giữ hàng GIỮA; request đầu vào trước (giữ hàng này, chờ
 * hàng giữa), request sau vào sau (giữ hàng kia, chờ hàng giữa), H nhả → vòng chờ nếu hai bên khoá ngược thứ tự.
 * Khẳng định: bộ đếm deadlock của Postgres không tăng và không request nào trả 5xx / 409 do khoá.
 */
class TaskRowDeadlockMatrixApiTest extends IntegrationTestBase {

    private enum Op { CLOSE, REOPEN_UNDO, DELETE, MOVE, BATCH_CLOSE, BATCH_DELETE, BATCH_MOVE, BATCH_REOPEN }

    @Autowired
    private DataSource dataSource;

    static Stream<Arguments> cases() {
        List<Arguments> list = new ArrayList<>();
        for (Op op : Op.values()) {
            for (boolean mixedSign : new boolean[] {false, true}) {
                for (boolean insertDescending : new boolean[] {false, true}) {
                    for (boolean opFirst : new boolean[] {false, true}) {
                        list.add(Arguments.of(op, mixedSign, insertDescending, opFirst));
                    }
                }
            }
        }
        return list.stream();
    }

    private static UUID randomWithSign(boolean negativeMsb) {
        while (true) {
            UUID id = UUID.randomUUID();
            if ((id.getMostSignificantBits() < 0) == negativeMsb) {
                return id;
            }
        }
    }

    private static List<UUID> ids(boolean loOpposite) {
        List<UUID> ids = new ArrayList<>(List.of(randomWithSign(loOpposite), randomWithSign(false), randomWithSign(false)));
        Collections.sort(ids);
        return ids;
    }

    private void insertTask(UUID id, UUID user, UUID project, UUID parent, String content, long sortOrder, boolean done) {
        jdbcTemplate.update("insert into tasks (id, workspace_id, project_id, parent_id, creator_id, content, priority, "
                        + "sort_order, completed_at, completion_batch_id, created_at, updated_at) values (?, "
                        + "(select workspace_id from projects where id = ?), ?, ?, ?, ?, 1, ?, "
                        + (done ? "now(), ?" : "null, null") + ", now(), now())",
                done ? new Object[] {id, project, project, parent, user, content, sortOrder, BATCH}
                        : new Object[] {id, project, project, parent, user, content, sortOrder});
    }

    private static final UUID BATCH = UUID.randomUUID();

    private String respond(ResultActions actions) throws Exception {
        var response = actions.andReturn().getResponse();
        return response.getStatus() + " " + response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private long deadlocks() {
        return jdbcTemplate.queryForObject(
                "select deadlocks from pg_stat_database where datname = current_database()", Long.class);
    }

    @ParameterizedTest(name = "{0} mixedSign={1} insertDesc={2} opFirst={3}")
    @MethodSource("cases")
    void noRowDeadlock(Op op, boolean mixedSign, boolean insertDescending, boolean opFirst) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID other = testData.project(user.id(), "Đích");
        boolean done = op == Op.REOPEN_UNDO || op == Op.BATCH_REOPEN;
        UUID parent = testData.task(user.id(), project, "Cha");
        if (done) {
            jdbcTemplate.update("update tasks set completed_at = now(), completion_batch_id = ? where id = ?", BATCH, parent);
        }
        List<UUID> ascending = ids(mixedSign);
        UUID lo = ascending.get(0);
        UUID mid = ascending.get(1);
        UUID hi = ascending.get(2);
        List<UUID> order = new ArrayList<>(ascending);
        if (insertDescending) {
            Collections.reverse(order);
        }
        int n = 1;
        for (UUID id : order) {
            long so = id.equals(lo) ? 1 : id.equals(mid) ? 2 : 3;
            insertTask(id, user.id(), project, parent, "C" + (n++), so, done);
        }
        long deadlocksBefore = deadlocks();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Connection held = dataSource.getConnection();
        held.setAutoCommit(false);
        try (PreparedStatement ps = held.prepareStatement("select id from tasks where id = ? for no key update")) {
            ps.setObject(1, mid);
            ps.executeQuery().close();
        }
        try {
            int holder = LockWaits.backendPid(held);
            var operation = (java.util.concurrent.Callable<String>) () -> switch (op) {
                case CLOSE -> respond(authPost(user.accessToken(), "/api/v1/tasks/" + parent + "/close"));
                case REOPEN_UNDO -> respond(authPost(user.accessToken(), "/api/v1/tasks/" + parent + "/reopen",
                        "{\"undo\": true}"));
                case DELETE -> respond(authDelete(user.accessToken(), "/api/v1/tasks/" + parent));
                case MOVE -> respond(authPost(user.accessToken(), "/api/v1/tasks/" + parent + "/move",
                        "{\"projectId\": \"%s\"}".formatted(other)));
                case BATCH_CLOSE -> respond(authPost(user.accessToken(), "/api/v1/tasks/batch",
                        "{\"action\": \"CLOSE\", \"taskIds\": [\"%s\"]}".formatted(parent)));
                case BATCH_DELETE -> respond(authPost(user.accessToken(), "/api/v1/tasks/batch",
                        "{\"action\": \"DELETE\", \"taskIds\": [\"%s\"]}".formatted(parent)));
                case BATCH_MOVE -> respond(authPost(user.accessToken(), "/api/v1/tasks/batch",
                        "{\"action\": \"MOVE\", \"taskIds\": [\"%s\"], \"projectId\": \"%s\"}".formatted(parent, other)));
                case BATCH_REOPEN -> respond(authPost(user.accessToken(), "/api/v1/tasks/batch",
                        "{\"action\": \"REOPEN\", \"undo\": true, \"taskIds\": [\"%s\"]}".formatted(parent)));
            };
            java.util.concurrent.Callable<String> reorder = () -> respond(authPost(user.accessToken(), "/api/v1/reorder",
                    "{\"type\": \"TASK\", \"id\": \"%s\", \"afterId\": \"%s\"}".formatted(hi, lo)));
            Future<String> first = pool.submit(opFirst ? operation : reorder);
            LockWaits.awaitBlockedBy(jdbcTemplate, holder, 1);
            Future<String> second = pool.submit(opFirst ? reorder : operation);
            LockWaits.awaitBlockedBy(jdbcTemplate, holder, 2);
            held.rollback();
            held.close();
            held = null;
            String opResult = (opFirst ? first : second).get(30, TimeUnit.SECONDS);
            String reorderResult = (opFirst ? second : first).get(30, TimeUnit.SECONDS);
            // xoá cha → con bị xoá theo: reorder đến sau nhận 404 là đúng; còn lại mọi request phải thành công
            Set<String> okOp = Set.of("200", "201", "204");
            assertThat(okOp).as("op " + op + ": " + opResult).contains(opResult.substring(0, 3));
            boolean deletes = op == Op.DELETE || op == Op.BATCH_DELETE;
            boolean moves = op == Op.MOVE || op == Op.BATCH_MOVE;
            // move trước: các con đã sang dự án khác → reorder đến sau nhận 409 version-conflict (không phải lỗi khoá)
            assertThat(deletes ? Set.of("200", "404") : moves ? Set.of("200", "409") : Set.of("200"))
                    .as("reorder: " + reorderResult).contains(reorderResult.substring(0, 3));
            if (reorderResult.startsWith("409")) {
                assertThat(reorderResult).contains("version-conflict");
            }
            assertThat(deadlocks()).as("pg_stat_database.deadlocks").isEqualTo(deadlocksBefore);
        } finally {
            if (held != null) {
                held.rollback();
                held.close();
            }
            pool.shutdownNow();
        }
    }
}
