package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.support.M13cApiTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Webhook (M13c F5) cho POST tasks/batch: mỗi task trong lô phát một sự kiện như thao tác đơn lẻ; sự kiện chỉ xếp hàng
 * cho người THỰC HIỆN request (người có token của app + scope data:*), nên người không đọc được dự án / người cộng tác
 * chỉ một dự án không nhận sự kiện của dự án khác. Kiểm bằng hàng trong webhook_deliveries (không gửi HTTP thật).
 */
class WebhookBatchApiTest extends M13cApiTestBase {

    private static final String BATCH = "/api/v1/tasks/batch";

    private RegisteredUser owner;
    private App app;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.update("delete from webhook_deliveries");
        owner = registerUser(uniqueEmail());
        app = createApp(owner.accessToken());
        authorizeApp(owner);
    }

    /** Chủ app đăng ký webhook cho các sự kiện; user nào cấp token data:* cho app thì nhận. */
    private void subscribe(String events) throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/developer/apps/" + app.id() + "/webhook")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\": \"https://hooks.example/in\", \"events\": %s}".formatted(events)))
                .andExpect(status().isCreated());
    }

    private void authorizeApp(RegisteredUser user) throws Exception {
        oauthToken(user.accessToken(), app, "data:read_write");
    }

    private UUID project(RegisteredUser user, String name) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/projects", "{\"name\": \"" + name + "\"}")
                .andExpect(status().isCreated()), "$.id");
    }

    private UUID task(RegisteredUser user, UUID project, String content) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/tasks",
                "{\"content\": \"" + content + "\", \"projectId\": \"" + project + "\"}")
                .andExpect(status().isCreated()), "$.id");
    }

    private void addToProject(RegisteredUser inviter, UUID project, RegisteredUser invitee) throws Exception {
        UUID invitation = readUuid(authPost(inviter.accessToken(), "/api/v1/projects/" + project + "/invitations",
                "{\"email\": \"" + invitee.email() + "\"}").andExpect(status().isCreated()), "$.id");
        authPost(invitee.accessToken(), "/api/v1/invitations/" + invitation + "/accept").andExpect(status().isOk());
    }

    private ResultActions batch(RegisteredUser user, String action, String extra, UUID... ids) throws Exception {
        String idList = String.join(", ", java.util.Arrays.stream(ids).map(id -> "\"" + id + "\"").toList());
        return authPost(user.accessToken(), BATCH,
                "{\"action\": \"" + action + "\", \"taskIds\": [" + idList + "]" + extra + "}");
    }

    private record Delivery(String eventName, String taskId, String payloadUserId) {
    }

    private List<Delivery> deliveries(UUID userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select event_name, payload from webhook_deliveries where user_id = ? order by created_at, id", userId);
        return rows.stream().map(row -> {
            String payload = String.valueOf(row.get("payload"));
            String taskId;
            try {
                taskId = JsonPath.read(payload, "$.eventData.id"); // batch / DELETE: {id}
            } catch (com.jayway.jsonpath.PathNotFoundException e) {
                taskId = JsonPath.read(payload, "$.eventData.task.id"); // close đơn lẻ: {task, ...}
            }
            return new Delivery((String) row.get("event_name"), taskId, JsonPath.read(payload, "$.userId"));
        }).toList();
    }

    private static List<String> taskIds(List<Delivery> deliveries) {
        return deliveries.stream().map(Delivery::taskId).toList();
    }

    @Test
    void batchClose_ofTwoTasks_queuesTwoItemCompletedEvents() throws Exception {
        subscribe("[\"item:completed\"]");
        UUID p = project(owner, "P");
        UUID t1 = task(owner, p, "một");
        UUID t2 = task(owner, p, "hai");

        batch(owner, "CLOSE", "", t1, t2).andExpect(status().isOk());

        List<Delivery> got = deliveries(owner.id());
        assertThat(got).hasSize(2).allSatisfy(d -> {
            assertThat(d.eventName()).isEqualTo("item:completed");
            assertThat(d.payloadUserId()).isEqualTo(owner.id().toString());
        });
        assertThat(taskIds(got)).containsExactlyInAnyOrder(t1.toString(), t2.toString());
    }

    @Test
    void everyBatchAction_mapsToTheSameEventAsTheSingleOperation() throws Exception {
        subscribe("[\"item:completed\", \"item:uncompleted\", \"item:updated\", \"item:deleted\"]");
        UUID p = project(owner, "P");
        UUID target = project(owner, "Đích");
        UUID t1 = task(owner, p, "một");
        UUID t2 = task(owner, p, "hai");
        UUID label = readUuid(authPost(owner.accessToken(), "/api/v1/labels", "{\"name\": \"nhan-batch\"}")
                .andExpect(status().isCreated()), "$.id");
        jdbcTemplate.update("delete from webhook_deliveries");

        batch(owner, "CLOSE", "", t1, t2).andExpect(status().isOk());
        batch(owner, "REOPEN", "", t1, t2).andExpect(status().isOk());
        batch(owner, "PRIORITY", ", \"priority\": 2", t1, t2).andExpect(status().isOk());
        batch(owner, "SCHEDULE", ", \"date\": \"2026-12-01\"", t1, t2).andExpect(status().isOk());
        batch(owner, "ADD_LABELS", ", \"labelIds\": [\"" + label + "\"]", t1, t2).andExpect(status().isOk());
        batch(owner, "REMOVE_LABELS", ", \"labelIds\": [\"" + label + "\"]", t1, t2).andExpect(status().isOk());
        batch(owner, "MOVE", ", \"projectId\": \"" + target + "\"", t1, t2).andExpect(status().isOk());
        batch(owner, "DELETE", "", t1, t2).andExpect(status().isOk());

        // thứ tự giữa các lô không bảo đảm (created_at bằng nhau khi đồng hồ test đứng yên) → đếm theo sự kiện
        List<String> events = deliveries(owner.id()).stream().map(Delivery::eventName).toList();
        assertThat(events).containsExactlyInAnyOrder(
                "item:completed", "item:completed",
                "item:uncompleted", "item:uncompleted",
                "item:updated", "item:updated", // PRIORITY
                "item:updated", "item:updated", // SCHEDULE
                "item:updated", "item:updated", // ADD_LABELS
                "item:updated", "item:updated", // REMOVE_LABELS
                "item:updated", "item:updated", // MOVE
                "item:deleted", "item:deleted");
        List<Delivery> deleted = deliveries(owner.id()).stream().filter(d -> d.eventName().equals("item:deleted")).toList();
        assertThat(taskIds(deleted)).containsExactlyInAnyOrder(t1.toString(), t2.toString());
    }

    @Test
    void batchOfAParentAndItsSubtask_firesOnlyForTheTasksTheUserPicked() throws Exception {
        subscribe("[\"item:completed\", \"item:deleted\"]");
        UUID p = project(owner, "P");
        UUID parent = task(owner, p, "cha");
        UUID child = readUuid(authPost(owner.accessToken(), "/api/v1/tasks", "{\"content\": \"con\", \"projectId\": \""
                + p + "\", \"parentId\": \"" + parent + "\"}").andExpect(status().isCreated()), "$.id");
        UUID other = task(owner, p, "khác");
        jdbcTemplate.update("delete from webhook_deliveries");

        // đóng cha: con bị kéo theo nhưng người dùng không chọn → một sự kiện (như close đơn lẻ của cha)
        batch(owner, "CLOSE", "", parent).andExpect(status().isOk());
        assertThat(taskIds(deliveries(owner.id()))).containsExactly(parent.toString());

        // chọn cả cha lẫn con + task khác: mỗi task chọn một sự kiện
        jdbcTemplate.update("delete from webhook_deliveries");
        batch(owner, "DELETE", "", parent, child, other).andExpect(status().isOk());
        assertThat(taskIds(deliveries(owner.id()))).containsExactlyInAnyOrder(parent.toString(), child.toString(), other.toString());
    }

    @Test
    void unsubscribedEvents_failedBatches_andIdempotentReplay_queueNothingExtra() throws Exception {
        subscribe("[\"item:completed\"]");
        UUID p = project(owner, "P");
        UUID t1 = task(owner, p, "một");
        UUID t2 = task(owner, p, "hai");

        // sự kiện không đăng ký (item:updated)
        batch(owner, "PRIORITY", ", \"priority\": 3", t1, t2).andExpect(status().isOk());
        // lô lỗi: id không tồn tại → 404, cả lô rollback, không sự kiện
        batch(owner, "CLOSE", "", t1, UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(deliveries(owner.id())).isEmpty();

        // gửi lại cùng Idempotency-Key: chỉ phát một lần
        String key = UUID.randomUUID().toString();
        String json = "{\"action\": \"CLOSE\", \"taskIds\": [\"" + t1 + "\", \"" + t2 + "\"]}";
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post(BATCH).header(HttpHeaders.AUTHORIZATION, bearer(owner.accessToken()))
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isOk());
        }
        assertThat(deliveries(owner.id())).hasSize(2);
    }

    @Test
    void aUserWhoCannotReadTheProject_getsNoEvent_andCannotTriggerOne() throws Exception {
        subscribe("[\"item:completed\"]");
        RegisteredUser stranger = registerUser(uniqueEmail());
        authorizeApp(stranger); // người lạ cũng đã cấp token data:* cho app, webhook sẽ nhận sự kiện của CHÍNH họ
        UUID p = project(owner, "Riêng của chủ");
        UUID t1 = task(owner, p, "một");
        UUID t2 = task(owner, p, "hai");

        // người lạ không đọc được → 404, không sự kiện cho ai
        batch(stranger, "CLOSE", "", t1, t2).andExpect(status().isNotFound());
        assertThat(deliveries(stranger.id())).isEmpty();
        assertThat(deliveries(owner.id())).isEmpty();

        // chủ đóng: chỉ chủ có hàng đợi, người lạ vẫn không có gì
        batch(owner, "CLOSE", "", t1, t2).andExpect(status().isOk());
        assertThat(deliveries(owner.id())).hasSize(2);
        assertThat(deliveries(stranger.id())).isEmpty();
    }

    @Test
    void collaboratorOfOneProject_getsNoEventForAnotherProjectOfTheOwner() throws Exception {
        subscribe("[\"item:completed\"]");
        RegisteredUser collaborator = registerUser(uniqueEmail());
        authorizeApp(collaborator);
        UUID shared = project(owner, "Chung");
        UUID secret = project(owner, "Riêng");
        addToProject(owner, shared, collaborator);
        UUID sharedTask = task(owner, shared, "việc chung");
        UUID secretTask = task(owner, secret, "việc riêng");

        // chủ đóng task ở CẢ HAI dự án (hai lô): người cộng tác không nhận gì của chủ
        batch(owner, "CLOSE", "", sharedTask).andExpect(status().isOk());
        batch(owner, "CLOSE", "", secretTask).andExpect(status().isOk());
        assertThat(deliveries(owner.id())).hasSize(2);
        assertThat(deliveries(collaborator.id())).isEmpty();

        // người cộng tác: đóng task dự án chung → sự kiện của chính họ; chạm vào dự án riêng của chủ → 404, không sự kiện
        batch(collaborator, "REOPEN", "", sharedTask).andExpect(status().isOk()); // item:uncompleted không đăng ký
        jdbcTemplate.update("delete from webhook_deliveries where user_id = ?", owner.id());
        batch(collaborator, "CLOSE", "", sharedTask).andExpect(status().isOk());
        batch(collaborator, "CLOSE", "", secretTask).andExpect(status().isNotFound());
        assertThat(taskIds(deliveries(collaborator.id()))).containsExactly(sharedTask.toString());
        assertThat(deliveries(owner.id())).isEmpty();
    }

    @Test
    void singleCloseOfOtherProject_isAlsoNotDeliveredToTheCollaborator() throws Exception {
        subscribe("[\"item:completed\"]");
        RegisteredUser collaborator = registerUser(uniqueEmail());
        authorizeApp(collaborator);
        UUID shared = project(owner, "Chung");
        UUID secret = project(owner, "Riêng");
        addToProject(owner, shared, collaborator);
        UUID sharedTask = task(owner, shared, "việc chung");
        UUID secretTask = task(owner, secret, "việc riêng");

        authPost(owner.accessToken(), "/api/v1/tasks/" + secretTask + "/close").andExpect(status().isOk());
        authPost(owner.accessToken(), "/api/v1/tasks/" + sharedTask + "/close").andExpect(status().isOk());

        assertThat(deliveries(owner.id())).hasSize(2);
        assertThat(deliveries(collaborator.id())).isEmpty();
        authPost(collaborator.accessToken(), "/api/v1/tasks/" + secretTask + "/close").andExpect(status().isNotFound());
        assertThat(deliveries(collaborator.id())).isEmpty();
    }

    // ---------------------------------------------------------------- lệnh sync task_batch (X3) phát webhook như REST

    private ResultActions syncBatch(RegisteredUser user, UUID commandUuid, String action, String extra, UUID... ids)
            throws Exception {
        String idList = String.join(", ", java.util.Arrays.stream(ids).map(id -> "\"" + id + "\"").toList());
        return authPost(user.accessToken(), "/api/v1/sync", "{\"syncToken\": \"*\", \"commands\": [{\"uuid\": \""
                + commandUuid + "\", \"type\": \"task_batch\", \"args\": {\"action\": \"" + action
                + "\", \"taskIds\": [" + idList + "]" + extra + "}}]}");
    }

    @Test
    void syncTaskBatch_queuesTheSameEventsAsRestBatch_onceEvenWhenTheCommandIsReplayed() throws Exception {
        subscribe("[\"item:completed\", \"item:deleted\"]");
        UUID p = project(owner, "P");
        UUID t1 = task(owner, p, "một");
        UUID t2 = task(owner, p, "hai");
        UUID t3 = task(owner, p, "ba");
        jdbcTemplate.update("delete from webhook_deliveries");

        UUID command = UUID.randomUUID();
        syncBatch(owner, command, "CLOSE", "", t1, t2).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("ok"));
        List<Delivery> got = deliveries(owner.id());
        assertThat(got).hasSize(2).allSatisfy(d -> assertThat(d.eventName()).isEqualTo("item:completed"));
        assertThat(taskIds(got)).containsExactlyInAnyOrder(t1.toString(), t2.toString());

        // cùng uuid lệnh gửi lại (offline retry): không chạy lại, không thêm sự kiện
        syncBatch(owner, command, "CLOSE", "", t1, t2).andExpect(status().isOk());
        assertThat(deliveries(owner.id())).hasSize(2);

        // action không đăng ký (PRIORITY) không xếp hàng gì; DELETE qua sync → item:deleted cho task chọn
        jdbcTemplate.update("delete from webhook_deliveries");
        syncBatch(owner, UUID.randomUUID(), "PRIORITY", ", \"priority\": 2", t3).andExpect(status().isOk());
        assertThat(deliveries(owner.id())).isEmpty();
        syncBatch(owner, UUID.randomUUID(), "DELETE", "", t3).andExpect(status().isOk());
        got = deliveries(owner.id());
        assertThat(got).hasSize(1);
        assertThat(got.get(0).eventName()).isEqualTo("item:deleted");
        assertThat(got.get(0).taskId()).isEqualTo(t3.toString());
    }

    @Test
    void syncTaskBatch_thatFails_queuesNothing_andOnlyTheActorGetsEvents() throws Exception {
        subscribe("[\"item:completed\"]");
        RegisteredUser stranger = registerUser(uniqueEmail());
        authorizeApp(stranger);
        UUID p = project(owner, "Riêng");
        UUID t1 = task(owner, p, "một");
        jdbcTemplate.update("delete from webhook_deliveries");

        // người lạ: lỗi 404 nằm trong results, không sự kiện cho ai
        syncBatch(stranger, UUID.randomUUID(), "CLOSE", "", t1).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("error"));
        assertThat(deliveries(stranger.id())).isEmpty();
        assertThat(deliveries(owner.id())).isEmpty();

        // lô có id không tồn tại: cả lô rollback, không sự kiện
        syncBatch(owner, UUID.randomUUID(), "CLOSE", "", t1, UUID.randomUUID()).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("error"));
        assertThat(deliveries(owner.id())).isEmpty();

        // chủ đóng qua sync: chỉ chủ có hàng đợi
        syncBatch(owner, UUID.randomUUID(), "CLOSE", "", t1).andExpect(status().isOk());
        assertThat(taskIds(deliveries(owner.id()))).containsExactly(t1.toString());
        assertThat(deliveries(stranger.id())).isEmpty();
    }
}
