package com.taskly.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import com.taskly.service.WebhookDispatchService;
import com.taskly.support.M13cApiTestBase;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Webhook: cấu hình, phát sự kiện, ký HMAC, thử lại tối đa 3 lần (plan M13c, F5) — gửi tới server giả của JDK. */
class WebhookApiTest extends M13cApiTestBase {

    @Autowired
    private WebhookDispatchService dispatcher;

    private HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);

    private record Received(Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    @BeforeEach
    void startServer() throws IOException {
        jdbcTemplate.update("delete from webhook_deliveries");
        responseStatus.set(200);
        received.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(Map.copyOf(exchange.getRequestHeaders()), body));
            exchange.sendResponseHeaders(responseStatus.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String hookUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private record Setup(RegisteredUser user, App app, String oauthToken, String webhookSecret) {
    }

    private Setup setup(String scope, String events) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        String oauth = oauthToken(user.accessToken(), app, scope);
        String secret = readString(authPut(user.accessToken(), app.id(), hookUrl(), events).andExpect(status().isCreated()),
                "$.secret");
        return new Setup(user, app, oauth, secret);
    }

    private org.springframework.test.web.servlet.ResultActions authPut(String token, UUID appId, String url,
                                                                        String events) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/developer/apps/" + appId + "/webhook")
                .header("Authorization", bearer(token)).contentType("application/json")
                .content("{\"url\": \"%s\", \"events\": %s}".formatted(url, events)));
    }

    private String createTask(String token, UUID userId, String content) throws Exception {
        return readString(authPost(token, "/api/v1/tasks", "{\"content\": \"%s\", \"projectId\": \"%s\"}"
                .formatted(content, testData.inboxOf(userId))).andExpect(status().isCreated()), "$.id");
    }

    private int pending(UUID userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from webhook_deliveries where user_id = ? and status = 'PENDING'", Integer.class, userId);
    }

    // ---- cấu hình ----

    @Test
    void put_createsThenReplaces_secretOnlyOnCreate_andStoredEncrypted() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        String base = "/api/v1/developer/apps/" + app.id() + "/webhook";
        authGet(user.accessToken(), base).andExpect(status().isNotFound());

        var created = authPut(user.accessToken(), app.id(), hookUrl(), "[\"item:added\", \"item:added\", \"item:completed\"]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.secret").value(startsWith("whs_")))
                .andExpect(jsonPath("$.events.length()").value(2));
        String secret = readString(created, "$.secret");
        Assertions.assertThat(jdbcTemplate.queryForObject("select secret_enc from webhooks where api_client_id = ?",
                String.class, app.id())).startsWith("v1:").doesNotContain(secret);

        authPut(user.accessToken(), app.id(), hookUrl(), "[\"project:added\"]")
                .andExpect(status().isOk()).andExpect(jsonPath("$.secret").value(nullValue()))
                .andExpect(jsonPath("$.events[0]").value("project:added"));
        authGet(user.accessToken(), base).andExpect(status().isOk()).andExpect(jsonPath("$.secret").value(nullValue()));
        authGet(user.accessToken(), "/api/v1/developer/apps").andExpect(jsonPath("$[0].webhook.url").value(hookUrl()));

        String rotated = readString(authPost(user.accessToken(), base + "/secret").andExpect(status().isOk()),
                "$.clientSecret");
        Assertions.assertThat(rotated).startsWith("whs_").isNotEqualTo(secret);
        authDelete(user.accessToken(), base).andExpect(status().isNoContent());
        authDelete(user.accessToken(), base).andExpect(status().isNotFound());
        authPost(user.accessToken(), base + "/secret").andExpect(status().isNotFound());
    }

    @Test
    void put_validatesUrlAndEvents_andOwnership() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        RegisteredUser stranger = registerUser(uniqueEmail());
        App app = createApp(user.accessToken());
        authPut(user.accessToken(), app.id(), "ftp://x.example/hook", "[\"item:added\"]").andExpect(status().isBadRequest());
        authPut(user.accessToken(), app.id(), "", "[\"item:added\"]").andExpect(status().isBadRequest());
        authPut(user.accessToken(), app.id(), hookUrl(), "[]").andExpect(status().isBadRequest());
        authPut(user.accessToken(), app.id(), hookUrl(), "[\"item:exploded\"]").andExpect(status().isBadRequest());
        authPut(user.accessToken(), app.id(), hookUrl(), "null").andExpect(status().isBadRequest());
        authPut(stranger.accessToken(), app.id(), hookUrl(), "[\"item:added\"]").andExpect(status().isNotFound());
    }

    // ---- phát sự kiện + ký + gửi ----

    @Test
    void itemAdded_isDeliveredSigned_withTheObjectAsEventData() throws Exception {
        Setup s = setup("data:read_write", "[\"item:added\", \"item:completed\"]");
        String taskId = createTask(s.user().accessToken(), s.user().id(), "Việc có webhook");
        Assertions.assertThat(pending(s.user().id())).isEqualTo(1);

        dispatcher.dispatchDue();

        Assertions.assertThat(received).hasSize(1);
        Received request = received.get(0);
        Assertions.assertThat(JsonPath.<String>read(request.body(), "$.eventName")).isEqualTo("item:added");
        Assertions.assertThat(JsonPath.<String>read(request.body(), "$.eventData.id")).isEqualTo(taskId);
        Assertions.assertThat(JsonPath.<String>read(request.body(), "$.eventData.content")).isEqualTo("Việc có webhook");
        Assertions.assertThat(JsonPath.<String>read(request.body(), "$.userId")).isEqualTo(s.user().id().toString());
        Assertions.assertThat(JsonPath.<String>read(request.body(), "$.version")).isEqualTo("1");
        Assertions.assertThat(request.header("X-Taskly-Hmac-SHA256"))
                .isEqualTo(WebhookDispatchService.sign(s.webhookSecret(), request.body()));
        Assertions.assertThat(request.header("X-Taskly-Event")).isEqualTo("item:added");
        Assertions.assertThat(request.header("X-Taskly-Delivery-ID"))
                .isEqualTo(JsonPath.<String>read(request.body(), "$.deliveryId"));
        Assertions.assertThat(jdbcTemplate.queryForObject(
                "select status from webhook_deliveries where user_id = ?", String.class, s.user().id())).isEqualTo("SENT");

        // đã SENT thì không gửi lại
        dispatcher.dispatchDue();
        Assertions.assertThat(received).hasSize(1);

        authPost(s.user().accessToken(), "/api/v1/tasks/" + taskId + "/close").andExpect(status().isOk());
        dispatcher.dispatchDue();
        Assertions.assertThat(received).hasSize(2);
        Assertions.assertThat(JsonPath.<String>read(received.get(1).body(), "$.eventName")).isEqualTo("item:completed");
    }

    @Test
    void deleteEvent_carriesOnlyTheId_andUnsubscribedEventsAreNotQueued() throws Exception {
        Setup s = setup("data:read_write", "[\"item:deleted\"]");
        String taskId = createTask(s.user().accessToken(), s.user().id(), "Sẽ xoá");
        Assertions.assertThat(pending(s.user().id())).isZero(); // item:added không đăng ký

        authDelete(s.user().accessToken(), "/api/v1/tasks/" + taskId).andExpect(status().isNoContent());
        dispatcher.dispatchDue();
        Assertions.assertThat(received).hasSize(1);
        Assertions.assertThat(JsonPath.<String>read(received.get(0).body(), "$.eventName")).isEqualTo("item:deleted");
        Assertions.assertThat(JsonPath.<String>read(received.get(0).body(), "$.eventData.id")).isEqualTo(taskId);
    }

    @Test
    void eventsAlsoComeFromOAuthCalls_andIdempotentReplayDoesNotFire() throws Exception {
        Setup s = setup("data:read_write", "[\"item:added\", \"label:added\", \"project:added\"]");
        UUID clientId = UUID.randomUUID();
        String body = "{\"id\": \"%s\", \"content\": \"Theo id\", \"projectId\": \"%s\"}"
                .formatted(clientId, testData.inboxOf(s.user().id()));
        authPost(s.oauthToken(), "/api/v1/tasks", body).andExpect(status().isCreated());
        authPost(s.oauthToken(), "/api/v1/tasks", body).andExpect(status().isOk()); // phát lại theo id client
        authPost(s.oauthToken(), "/api/v1/labels", "{\"name\": \"nhan-webhook\"}").andExpect(status().isCreated());
        authPost(s.user().accessToken(), "/api/v1/projects", "{\"name\": \"Dự án webhook\"}").andExpect(status().isCreated());
        Assertions.assertThat(pending(s.user().id())).isEqualTo(3);
    }

    @Test
    void failedRequestsAndReadsDoNotFire() throws Exception {
        Setup s = setup("data:read_write", "[\"item:updated\", \"item:added\"]");
        authGet(s.user().accessToken(), "/api/v1/tasks/" + UUID.randomUUID()).andExpect(status().isNotFound());
        authPatch(s.user().accessToken(), "/api/v1/tasks/" + UUID.randomUUID(), "{\"content\": \"x\", \"version\": 0}")
                .andExpect(status().isNotFound());
        authPost(s.user().accessToken(), "/api/v1/tasks", "{\"content\": \"\", \"projectId\": \"%s\"}"
                .formatted(testData.inboxOf(s.user().id()))).andExpect(status().isBadRequest());
        Assertions.assertThat(pending(s.user().id())).isZero();
    }

    @Test
    void onlyUsersWithAValidDataTokenTriggerDelivery() throws Exception {
        RegisteredUser owner = registerUser(uniqueEmail());
        App app = createApp(owner.accessToken());
        authPut(owner.accessToken(), app.id(), hookUrl(), "[\"item:added\"]").andExpect(status().isCreated());

        // chưa ai cấp quyền
        createTask(owner.accessToken(), owner.id(), "Không ai cấp quyền");
        Assertions.assertThat(pending(owner.id())).isZero();

        // chỉ có scope task:add (không đọc dữ liệu) thì không nhận webhook
        RegisteredUser addOnly = registerUser(uniqueEmail());
        oauthToken(addOnly.accessToken(), app, "task:add");
        createTask(addOnly.accessToken(), addOnly.id(), "Chỉ task:add");
        Assertions.assertThat(pending(addOnly.id())).isZero();

        // có token data:read thì nhận; thu hồi thì ngừng
        RegisteredUser reader = registerUser(uniqueEmail());
        oauthToken(reader.accessToken(), app, "data:read");
        createTask(reader.accessToken(), reader.id(), "Có token đọc");
        Assertions.assertThat(pending(reader.id())).isEqualTo(1);
        authDelete(reader.accessToken(), "/api/v1/me/authorized-apps/" + app.clientId()).andExpect(status().isNoContent());
        createTask(reader.accessToken(), reader.id(), "Sau khi thu hồi");
        Assertions.assertThat(pending(reader.id())).isEqualTo(1);
    }

    // ---- thử lại ----

    @Test
    void serverError_retriesExactlyThreeTimesWithGrowingDelay_thenFails() throws Exception {
        Setup s = setup("data:read_write", "[\"item:added\"]");
        responseStatus.set(500);
        createTask(s.user().accessToken(), s.user().id(), "Thử lại");

        dispatcher.dispatchDue();
        Assertions.assertThat(received).hasSize(1);
        dispatcher.dispatchDue(); // chưa tới hạn
        Assertions.assertThat(received).hasSize(1);

        clock.advance(Duration.ofMinutes(1));
        dispatcher.dispatchDue();
        Assertions.assertThat(received).hasSize(2);
        clock.advance(Duration.ofMinutes(4)); // mới 4 / 5 phút
        dispatcher.dispatchDue();
        Assertions.assertThat(received).hasSize(2);
        clock.advance(Duration.ofMinutes(1));
        dispatcher.dispatchDue();
        Assertions.assertThat(received).hasSize(3);
        clock.advance(Duration.ofMinutes(30));
        dispatcher.dispatchDue();
        Assertions.assertThat(received).hasSize(4);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, attempts, last_status_code from webhook_deliveries where user_id = ?", s.user().id());
        Assertions.assertThat(row).containsEntry("status", "FAILED").containsEntry("attempts", 4)
                .containsEntry("last_status_code", 500);
        clock.advance(Duration.ofHours(2));
        dispatcher.dispatchDue();
        Assertions.assertThat(received).hasSize(4); // hết thử
    }

    @Test
    void recoversWhenTheServerComesBack() throws Exception {
        Setup s = setup("data:read_write", "[\"item:added\"]");
        responseStatus.set(503);
        createTask(s.user().accessToken(), s.user().id(), "Phục hồi");
        dispatcher.dispatchDue();
        responseStatus.set(200);
        clock.advance(Duration.ofMinutes(1));
        dispatcher.dispatchDue();
        Assertions.assertThat(received).hasSize(2);
        Assertions.assertThat(jdbcTemplate.queryForObject("select status from webhook_deliveries where user_id = ?",
                String.class, s.user().id())).isEqualTo("SENT");
    }

    @Test
    void unreachableServerCountsAsAFailedAttempt() throws Exception {
        Setup s = setup("data:read_write", "[\"item:added\"]");
        int deadPort = server.getAddress().getPort();
        server.stop(0);
        createTask(s.user().accessToken(), s.user().id(), "Server chết");
        dispatcher.dispatchDue();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, attempts from webhook_deliveries where user_id = ?", s.user().id());
        Assertions.assertThat(row).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        Assertions.assertThat(deadPort).isPositive();
        // dựng lại server để @AfterEach dừng không lỗi
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @Test
    void redirectsAreNotFollowed() throws Exception {
        Setup s = setup("data:read_write", "[\"item:added\"]");
        responseStatus.set(302);
        createTask(s.user().accessToken(), s.user().id(), "Chuyển hướng");
        dispatcher.dispatchDue();
        Assertions.assertThat(received).hasSize(1);
        Assertions.assertThat(jdbcTemplate.queryForObject("select attempts from webhook_deliveries where user_id = ?",
                Integer.class, s.user().id())).isEqualTo(1);
    }

    @Test
    void deletingTheWebhookDropsQueuedDeliveries() throws Exception {
        Setup s = setup("data:read_write", "[\"item:added\"]");
        createTask(s.user().accessToken(), s.user().id(), "Sẽ bị bỏ");
        authDelete(s.user().accessToken(), "/api/v1/developer/apps/" + s.app().id() + "/webhook")
                .andExpect(status().isNoContent());
        Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from webhook_deliveries where user_id = ?", Integer.class, s.user().id())).isZero();
        dispatcher.dispatchDue();
        Assertions.assertThat(received).isEmpty();
    }
}
