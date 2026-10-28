package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.security.EmailInboundRateLimiter;
import com.taskly.security.InboundSignatureVerifier;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * M13a — webhook inbound trung lập nhà cung cấp: chữ ký HMAC (bí mật giả ở application-test.yml), thư tới địa chỉ dự án /
 * Inbox → task mới, tới địa chỉ task → nối vào mô tả (quyết định tạm, M8b chưa có comment), chống trùng message-id,
 * giới hạn tần suất (test: 3 thư / giờ / địa chỉ, 5 request / phút / IP).
 */
class EmailInboundApiTest extends IntegrationTestBase {

    private static final String URI = "/api/v1/integrations/email/inbound";
    private static final String SECRET = "test-only-email-webhook-secret-not-real";

    @Autowired
    private EmailInboundRateLimiter rateLimiter;

    @BeforeEach
    void clearRateLimits() {
        rateLimiter.clear();
    }

    private static String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10.77." + r.nextInt(250) + "." + r.nextInt(250);
    }

    private ResultActions deliver(String json) throws Exception {
        return deliver(json, randomIp());
    }

    private ResultActions deliver(String json, String ip) throws Exception {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        long ts = clock.instant().getEpochSecond();
        return mockMvc.perform(post(URI)
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Taskly-Timestamp", Long.toString(ts))
                .header("X-Taskly-Signature", InboundSignatureVerifier.sign(SECRET, ts, body))
                .content(body));
    }

    private static String mail(String messageId, String to, String subject, String text) {
        return """
                {"messageId": %s, "from": "Sender <sender@example.com>", "to": %s, "subject": %s, "text": %s,
                 "attachments": [{"filename": "a.pdf", "contentType": "application/pdf", "size": 10}]}
                """.formatted(quote(messageId), to, quote(subject), quote(text));
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static String newMessageId() {
        return "<" + UUID.randomUUID() + "@mail.example.com>";
    }

    private String addressOf(RegisteredUser user, String base) throws Exception {
        return readString(authPut(user.accessToken(), base + "/email-address").andExpect(status().isOk()), "$.address");
    }

    private List<Map<String, Object>> tasksIn(UUID projectId) {
        return jdbcTemplate.queryForList("select * from tasks where project_id = ? order by sort_order", projectId);
    }

    @Test
    void mailToProjectAddressCreatesTaskWithSyntaxApplied() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "Việc nhà");
        UUID label = readUuid(authPost(user.accessToken(), "/api/v1/labels", "{\"name\": \"errands\"}")
                .andExpect(status().isCreated()), "$.id");
        String address = addressOf(user, "/api/v1/projects/" + project);

        deliver(mail(newMessageId(), quote(address), "Fwd: Buy milk p2 @Errands date tomorrow 5pm +bob",
                "Line 1\nLine 2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.appended").value(0))
                .andExpect(jsonPath("$.ignored").value(0));

        List<Map<String, Object>> tasks = tasksIn(project);
        assertThat(tasks).hasSize(1);
        Map<String, Object> task = tasks.get(0);
        assertThat(task.get("content")).isEqualTo("Buy milk +bob");
        assertThat(task.get("description")).isEqualTo("Line 1\nLine 2");
        assertThat(((Number) task.get("priority")).intValue()).isEqualTo(2);
        ZoneId zone = ZoneId.of(jdbcTemplate.queryForObject("select time_zone from users where id = ?", String.class,
                user.id()));
        assertThat(((Timestamp) task.get("due_datetime")).toLocalDateTime())
                .isEqualTo(LocalDate.ofInstant(clock.instant(), zone).plusDays(1).atTime(17, 0));
        assertThat(task.get("creator_id")).isEqualTo(user.id());
        assertThat(jdbcTemplate.queryForList("select label_id from task_labels where task_id = ?", UUID.class,
                task.get("id"))).containsExactly(label);
    }

    /** PERM: Thành viên (không phải Admin) của dự án NHÓM — thư tới địa chỉ dự án / task bị từ chối, không ghi gì. */
    @Test
    void mailFromTeamMember_toTeamProjectOrTaskAddress_isRejected() throws Exception {
        RegisteredUser admin = registerUser(uniqueEmail());
        RegisteredUser member = registerUser(uniqueEmail());
        UUID team = readUuid(authPost(admin.accessToken(), "/api/v1/workspaces", "{\"name\": \"Nhóm\"}")
                .andExpect(status().isCreated()), "$.id");
        UUID invitation = readUuid(authPost(admin.accessToken(), "/api/v1/workspaces/" + team + "/invitations",
                "{\"email\": \"" + member.email() + "\"}").andExpect(status().isCreated()), "$.id");
        authPost(member.accessToken(), "/api/v1/invitations/" + invitation + "/accept").andExpect(status().isOk());
        UUID project = readUuid(authPost(admin.accessToken(), "/api/v1/projects", "{\"name\": \"Nhóm\", "
                + "\"workspaceId\": \"" + team + "\"}").andExpect(status().isCreated()), "$.id");
        authPost(member.accessToken(), "/api/v1/projects/" + project + "/join").andExpect(status().isOk());
        UUID task = readUuid(authPost(admin.accessToken(), "/api/v1/tasks", "{\"content\": \"Việc\", "
                + "\"projectId\": \"" + project + "\"}").andExpect(status().isCreated()), "$.id");
        String projectAddress = addressOf(member, "/api/v1/projects/" + project);
        String taskAddress = addressOf(member, "/api/v1/tasks/" + task);

        deliver(mail(newMessageId(), quote(projectAddress), "Việc mới", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.rejected").value(1));
        deliver(mail(newMessageId(), quote(taskAddress), "Nối thêm", "nội dung"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appended").value(0))
                .andExpect(jsonPath("$.rejected").value(1));
        assertThat(tasksIn(project)).extracting(t -> t.get("content")).containsExactly("Việc");
        assertThat(tasksIn(project).get(0).get("description")).isEqualTo("");
    }

    @Test
    void mailToInboxAddress_toAsSingleStringWithDisplayName_caseInsensitive() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        String address = addressOf(user, "/api/v1/projects/" + inbox);

        deliver(mail(newMessageId(), quote("Taskly Inbox <" + address.toUpperCase() + ">, other@example.com"),
                "Read article", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.ignored").value(0));
        assertThat(tasksIn(inbox)).extracting(t -> t.get("content")).containsExactly("Read article");
    }

    @Test
    void emptySubjectFallsBackToTheFirstLineOfText_andEmptyMailIsIgnored() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        String address = addressOf(user, "/api/v1/projects/" + project);

        deliver(mail(newMessageId(), quote(address), "", "\n  First line  \nSecond"))
                .andExpect(jsonPath("$.created").value(1));
        deliver(mail(newMessageId(), quote(address), null, "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ignored").value(1));
        assertThat(tasksIn(project)).extracting(t -> t.get("content")).containsExactly("First line");
    }

    @Test
    void sameMessageIdIsProcessedOncePerAddress() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID other = testData.project(user.id(), "Q");
        String address = addressOf(user, "/api/v1/projects/" + project);
        String otherAddress = addressOf(user, "/api/v1/projects/" + other);
        String messageId = newMessageId();

        deliver(mail(messageId, "[" + quote(address) + ", " + quote(otherAddress) + "]", "Once", "body"))
                .andExpect(jsonPath("$.created").value(2));
        deliver(mail(messageId, "[" + quote(address) + ", " + quote(otherAddress) + "]", "Once", "body"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.duplicates").value(2));
        assertThat(tasksIn(project)).hasSize(1);
        assertThat(tasksIn(other)).hasSize(1);
    }

    @Test
    void mailToTaskAddressAppendsToTheDescription() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID task = readUuid(authPost(user.accessToken(), "/api/v1/tasks",
                "{\"projectId\": \"%s\", \"content\": \"T\", \"description\": \"Old\"}".formatted(project))
                .andExpect(status().isCreated()), "$.id");
        long version = testData.taskVersion(task);
        String address = addressOf(user, "/api/v1/tasks/" + task);

        deliver(mail(newMessageId(), quote(address), "Re: Notes", "More details"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appended").value(1))
                .andExpect(jsonPath("$.created").value(0));

        assertThat(jdbcTemplate.queryForObject("select description from tasks where id = ?", String.class, task))
                .isEqualTo("Old\n\nRe: Notes\nMore details");
        assertThat(testData.taskVersion(task)).isEqualTo(version + 1);
        assertThat(tasksIn(project)).hasSize(1);
    }

    @Test
    void unknownAddressesAndOtherDomainsAreIgnored() throws Exception {
        deliver(mail(newMessageId(), "[\"abcdefghijklmnopqrstuvwxyz@in.taskly.test\", \"someone@example.com\"]",
                "X", "Y"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.ignored").value(1));
    }

    @Test
    void regeneratedOrDeletedTargetsNoLongerReceive() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID task = testData.task(user.id(), project, "T");
        String old = addressOf(user, "/api/v1/projects/" + project);
        authPost(user.accessToken(), "/api/v1/projects/" + project + "/email-address/regenerate")
                .andExpect(status().isOk());
        String taskAddress = addressOf(user, "/api/v1/tasks/" + task);
        testData.deleteTask(task);

        deliver(mail(newMessageId(), "[" + quote(old) + ", " + quote(taskAddress) + "]", "X", "Y"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ignored").value(2));
        assertThat(tasksIn(project)).isEmpty();
    }

    @Test
    void archivedProjectRejectsTheMail() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        String address = addressOf(user, "/api/v1/projects/" + project);
        testData.archiveProject(project);

        deliver(mail(newMessageId(), quote(address), "X", "Y"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejected").value(1));
        assertThat(tasksIn(project)).isEmpty();
        // bị từ chối thì không ghi dấu chống trùng: bỏ lưu trữ rồi gửi lại cùng thư vẫn tạo được task
    }

    @Test
    void rejectedMailIsNotMarkedAsSeen() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        String address = addressOf(user, "/api/v1/projects/" + project);
        String messageId = newMessageId();
        testData.archiveProject(project);
        deliver(mail(messageId, quote(address), "X", "Y")).andExpect(jsonPath("$.rejected").value(1));

        jdbcTemplate.update("update projects set archived_at = null where id = ?", project);
        deliver(mail(messageId, quote(address), "X", "Y")).andExpect(jsonPath("$.created").value(1));
    }

    @Test
    void badMissingOrStaleSignatureIs401_andNothingHappens() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        String address = addressOf(user, "/api/v1/projects/" + project);
        byte[] body = mail(newMessageId(), quote(address), "X", "Y").getBytes(StandardCharsets.UTF_8);
        long now = clock.instant().getEpochSecond();

        mockMvc.perform(post(URI).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Taskly-Timestamp", Long.toString(now))
                        .header("X-Taskly-Signature", InboundSignatureVerifier.sign("wrong-secret", now, body)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URI).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        long stale = now - Duration.ofMinutes(6).toSeconds();
        mockMvc.perform(post(URI).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Taskly-Timestamp", Long.toString(stale))
                        .header("X-Taskly-Signature", InboundSignatureVerifier.sign(SECRET, stale, body)))
                .andExpect(status().isUnauthorized());
        // Bearer hỏng / hết hạn không làm hỏng webhook (đường public bỏ qua header Authorization)
        mockMvc.perform(post(URI).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("Authorization", "Bearer not-a-jwt")
                        .header("X-Taskly-Timestamp", Long.toString(now))
                        .header("X-Taskly-Signature", InboundSignatureVerifier.sign(SECRET, now, body)))
                .andExpect(status().isOk());
        assertThat(tasksIn(project)).hasSize(1);
    }

    @Test
    void malformedJsonOrMissingMessageIdIs400() throws Exception {
        deliver("{not json").andExpect(status().isBadRequest());
        deliver(mail(null, "\"a@in.taskly.test\"", "X", "Y")).andExpect(status().isBadRequest());
        deliver(mail("  ", "\"a@in.taskly.test\"", "X", "Y")).andExpect(status().isBadRequest());
        deliver(mail("x".repeat(999), "\"a@in.taskly.test\"", "X", "Y")).andExpect(status().isBadRequest());
    }

    @Test
    void perAddressRateLimit_429WhenEveryRecipientIsOverTheLimit() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        String address = addressOf(user, "/api/v1/projects/" + project);
        for (int i = 0; i < 3; i++) {
            deliver(mail(newMessageId(), quote(address), "Task " + i, "")).andExpect(jsonPath("$.created").value(1));
        }
        deliver(mail(newMessageId(), quote(address), "Too many", ""))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
        assertThat(tasksIn(project)).hasSize(3);

        clock.advance(Duration.ofHours(1).plusSeconds(1));
        deliver(mail(newMessageId(), quote(address), "Later", "")).andExpect(jsonPath("$.created").value(1));
    }

    @Test
    void perIpRateLimitAppliesBeforeTheSignatureCheck() throws Exception {
        String ip = randomIp();
        for (int i = 0; i < 5; i++) {
            deliver(mail(newMessageId(), "[]", "X", "Y"), ip).andExpect(status().isOk());
        }
        deliver(mail(newMessageId(), "[]", "X", "Y"), ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));
    }
}
