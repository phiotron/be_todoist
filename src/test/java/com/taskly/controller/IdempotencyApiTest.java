package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.common.error.ApiException;
import com.taskly.security.OpaqueTokens;
import com.taskly.service.IdempotencyService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

class IdempotencyApiTest extends IntegrationTestBase {

    private static final String PROJECTS = "/api/v1/projects";

    @Autowired
    private IdempotencyService idempotencyService;

    private ResultActions postWithKey(String token, String uri, String key, String json) throws Exception {
        return mockMvc.perform(post(uri).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private int countProjectsNamed(UUID userId, String name) {
        return jdbcTemplate.queryForObject("select count(*) from projects where workspace_id = ? and name = ?",
                Integer.class, testData.workspaceOf(userId), name);
    }

    private int keyRows(UUID userId, String key) {
        return jdbcTemplate.queryForObject("select count(*) from idempotency_keys where user_id = ? and idem_key = ?",
                Integer.class, userId, key);
    }

    private Instant expiresAt(UUID userId, String key) {
        return jdbcTemplate.queryForObject("select expires_at from idempotency_keys where user_id = ? and idem_key = ?",
                Timestamp.class, userId, key).toInstant();
    }

    /** status null = hàng "đang xử lý" (spec 12.11). */
    private void insertKey(UUID userId, String key, String path, String body, Integer status, Instant createdAt,
                           Instant expiresAt) {
        jdbcTemplate.update("insert into idempotency_keys (id, user_id, idem_key, method, path, request_hash, status, "
                        + "response_body, expires_at, created_at, updated_at) values (?, ?, ?, 'POST', ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, key, path, OpaqueTokens.sha256Hex(body), status,
                status == null ? null : "{\"id\":\"cu\"}", Timestamp.from(expiresAt), Timestamp.from(createdAt),
                Timestamp.from(createdAt));
    }

    @Test
    void sameKeySameBody_replaysFirstResult_withoutCreatingTwice() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String key = UUID.randomUUID().toString();
        String json = "{\"name\": \"Một lần\"}";
        Instant now = clock.instant();

        String firstId = readString(postWithKey(user.accessToken(), PROJECTS, key, json)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replay")), "$.id");
        assertThat(expiresAt(user.id(), key)).as("xong 2xx: hết hạn sau 24 giờ")
                .isBetween(now.plus(Duration.ofHours(23)), now.plus(Duration.ofHours(25)));
        postWithKey(user.accessToken(), PROJECTS, key, json)
                .andExpect(status().isCreated()) // phát lại ĐÚNG status gốc
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(firstId))
                .andExpect(jsonPath("$.name").value("Một lần")); // và đúng body gốc

        assertThat(countProjectsNamed(user.id(), "Một lần")).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentBody_returns422_andKeepsTheStoredResult() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String key = UUID.randomUUID().toString();
        String firstId = readString(postWithKey(user.accessToken(), PROJECTS, key, "{\"name\": \"A\"}")
                .andExpect(status().isCreated()), "$.id");

        postWithKey(user.accessToken(), PROJECTS, key, "{\"name\": \"B\"}")
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.detail").value("Idempotency-Key đã được dùng cho nội dung khác"))
                .andExpect(jsonPath("$.code").value("idempotency-mismatch"));

        // 422 của begin() không được xoá kết quả đã lưu: gửi lại nội dung gốc vẫn được phát lại
        postWithKey(user.accessToken(), PROJECTS, key, "{\"name\": \"A\"}")
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(firstId));
    }

    @Test
    void withoutKey_eachRequestCreates() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPost(user.accessToken(), PROJECTS, "{\"name\": \"Không khoá\"}").andExpect(status().isCreated());
        authPost(user.accessToken(), PROJECTS, "{\"name\": \"Không khoá\"}").andExpect(status().isCreated());

        assertThat(countProjectsNamed(user.id(), "Không khoá")).isEqualTo(2);
    }

    @Test
    void invalidKey_returns400() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        postWithKey(user.accessToken(), PROJECTS, "", "{\"name\": \"x\"}").andExpect(status().isBadRequest());
        postWithKey(user.accessToken(), PROJECTS, "k".repeat(65), "{\"name\": \"x\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Idempotency-Key phải từ 1 đến 64 ký tự"));
    }

    @Test
    void failedRequest400_deletesTheLockRow_soRetryWithFixedBodyWorks() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String key = UUID.randomUUID().toString();

        postWithKey(user.accessToken(), PROJECTS, key, "{\"name\": \"   \"}").andExpect(status().isBadRequest());
        assertThat(keyRows(user.id(), key)).as("lỗi 4xx → hàng khoá bị xoá").isZero();

        postWithKey(user.accessToken(), PROJECTS, key, "{\"name\": \"Đã sửa\"}")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replay"));
        assertThat(keyRows(user.id(), key)).isEqualTo(1);
    }

    @Test
    void failedRequest404_deletesTheLockRowToo() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String key = UUID.randomUUID().toString();
        String uri = "/api/v1/projects/" + UUID.randomUUID() + "/sections";

        postWithKey(user.accessToken(), uri, key, "{\"name\": \"S\"}").andExpect(status().isNotFound());

        assertThat(keyRows(user.id(), key)).isZero();
    }

    @Test
    void keyStillInProgressAndNotExpired_returns409_andKeepsTheRow() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String json = "{\"name\": \"Đang xử lý\"}";
        Instant now = clock.instant();
        String busy = UUID.randomUUID().toString();
        insertKey(user.id(), busy, PROJECTS, json, null, now, now.plus(Duration.ofSeconds(60)));

        postWithKey(user.accessToken(), PROJECTS, busy, json)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Yêu cầu trước với cùng Idempotency-Key đang được xử lý"))
                .andExpect(jsonPath("$.code").value("idempotency-in-progress"));

        assertThat(keyRows(user.id(), busy)).isEqualTo(1);
        assertThat(countProjectsNamed(user.id(), "Đang xử lý")).isZero();
    }

    @Test
    void serverDiedMidway_expiredInProgressRow_isTakenOverByTheNewRequest() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String json = "{\"name\": \"Chiếm lại\"}";
        Instant now = clock.instant();
        String stale = UUID.randomUUID().toString();
        // request cũ chèn hàng khoá rồi server chết: không bao giờ ghi kết quả, hàng quá hạn (60 giây) từ lâu
        insertKey(user.id(), stale, PROJECTS, json, null, now.minus(Duration.ofMinutes(2)),
                now.minus(Duration.ofMinutes(1)));

        postWithKey(user.accessToken(), PROJECTS, stale, json)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replay"));

        assertThat(keyRows(user.id(), stale)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select status from idempotency_keys where user_id = ? and idem_key = ?",
                Integer.class, user.id(), stale)).isEqualTo(201);
        assertThat(countProjectsNamed(user.id(), "Chiếm lại")).isEqualTo(1);
    }

    @Test
    void expiredCompletedKey_isProcessedAgain() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String json = "{\"name\": \"Hết hạn\"}";
        Instant now = clock.instant();
        String expired = UUID.randomUUID().toString();
        insertKey(user.id(), expired, PROJECTS, json, 201, now.minus(Duration.ofHours(25)), now.minus(Duration.ofHours(1)));

        postWithKey(user.accessToken(), PROJECTS, expired, json)
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replay"));
    }

    @Test
    void differentUsersWithSameKey_areIndependent() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        String key = UUID.randomUUID().toString();

        postWithKey(alice.accessToken(), PROJECTS, key, "{\"name\": \"Chung khoá\"}").andExpect(status().isCreated());
        postWithKey(bob.accessToken(), PROJECTS, key, "{\"name\": \"Chung khoá\"}")
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replay"));
    }

    @Test
    void sectionCreateIsCovered() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String uri = "/api/v1/projects/" + testData.project(user.id(), "P") + "/sections";
        String key = UUID.randomUUID().toString();

        String id = readString(postWithKey(user.accessToken(), uri, key, "{\"name\": \"S\"}")
                .andExpect(status().isCreated()), "$.id");
        postWithKey(user.accessToken(), uri, key, "{\"name\": \"S\"}")
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void begin_insertsTheLockRowBeforeProcessing_inItsOwnTransaction_withSixtySecondTtl() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String key = "k-" + UUID.randomUUID();
        Instant now = clock.instant();

        IdempotencyService.Outcome outcome = idempotencyService.begin(user.id(), key, "POST", PROJECTS, "hash-1");

        assertThat(outcome).isInstanceOf(IdempotencyService.Proceed.class);
        UUID recordId = ((IdempotencyService.Proceed) outcome).recordId();
        // Hàng đã COMMIT (đọc được từ kết nối khác): chưa có status = "đang xử lý", hết hạn sau ~60 giây
        assertThat(jdbcTemplate.queryForObject("select status from idempotency_keys where id = ?", Integer.class, recordId))
                .isNull();
        assertThat(expiresAt(user.id(), key)).isBetween(now.plusSeconds(55), now.plusSeconds(65));

        assertThatThrownBy(() -> idempotencyService.begin(user.id(), key, "POST", PROJECTS, "hash-1"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(409);
                    assertThat(ex.getBody().getProperties()).containsEntry("code", "idempotency-in-progress");
                });
        assertThatThrownBy(() -> idempotencyService.begin(user.id(), key, "POST", PROJECTS, "hash-khac"))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(422);
                    assertThat(ex.getBody().getProperties()).containsEntry("code", "idempotency-mismatch");
                });

        idempotencyService.complete(recordId, 201, "{\"id\":\"x\"}");
        assertThat(jdbcTemplate.queryForObject("select status from idempotency_keys where id = ?", Integer.class, recordId))
                .isEqualTo(201);
        assertThat(expiresAt(user.id(), key)).isBetween(now.plus(Duration.ofHours(23)), now.plus(Duration.ofHours(25)));
        assertThat(idempotencyService.begin(user.id(), key, "POST", PROJECTS, "hash-1"))
                .isEqualTo(new IdempotencyService.Replay(201, "{\"id\":\"x\"}"));

        String other = "k2-" + UUID.randomUUID();
        UUID second = ((IdempotencyService.Proceed) idempotencyService.begin(user.id(), other, "POST", PROJECTS, "h"))
                .recordId();
        idempotencyService.abandon(second); // request lỗi → xoá hàng
        assertThat(keyRows(user.id(), other)).isZero();
        assertThat(idempotencyService.begin(user.id(), other, "POST", PROJECTS, "h"))
                .isInstanceOf(IdempotencyService.Proceed.class);
    }

    @Test
    void complete_afterTheRowWasTakenOver_reportsLostClaim_andDoesNotTouchTheNewOwnersRow() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String key = "k-" + UUID.randomUUID();
        UUID slow = ((IdempotencyService.Proceed) idempotencyService.begin(user.id(), key, "POST", PROJECTS, "h"))
                .recordId();
        clock.advance(Duration.ofSeconds(61)); // hàng "đang xử lý" hết hạn 60 giây → request trùng chiếm lại
        UUID taker = ((IdempotencyService.Proceed) idempotencyService.begin(user.id(), key, "POST", PROJECTS, "h"))
                .recordId();
        assertThat(taker).isNotEqualTo(slow);

        assertThat(idempotencyService.complete(slow, 201, "{\"id\":\"cu\"}")).isFalse(); // 0 dòng: mất quyền
        assertThat(jdbcTemplate.queryForObject("select status from idempotency_keys where id = ?", Integer.class, taker))
                .isNull();
        assertThat(idempotencyService.complete(taker, 201, "{\"id\":\"moi\"}")).isTrue();
    }

    @Test
    void operationRunningLongerThanTheTtl_keepsItsLease_soADuplicateGets409InsteadOfRunningTwice() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String key = "k-" + UUID.randomUUID();
        UUID running = ((IdempotencyService.Proceed) idempotencyService.begin(user.id(), key, "POST", PROJECTS, "h"))
                .recordId();

        // thao tác vẫn đang chạy sau 150 giây (> 60 giây hạn ban đầu); luồng gia hạn chạy định kỳ trong lúc đó
        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofSeconds(30));
            idempotencyService.renewLeases();
        }

        assertThatThrownBy(() -> idempotencyService.begin(user.id(), key, "POST", PROJECTS, "h"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(409));
        assertThat(keyRows(user.id(), key)).isEqualTo(1);
        assertThat(idempotencyService.complete(running, 201, "{\"id\":\"cu\"}")).isTrue(); // vẫn còn quyền sở hữu
    }

    @Test
    void renewLeases_neverTouchesAFinishedRow() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String key = "k-" + UUID.randomUUID();
        UUID done = ((IdempotencyService.Proceed) idempotencyService.begin(user.id(), key, "POST", PROJECTS, "h")).recordId();
        idempotencyService.complete(done, 201, "{\"id\":\"cu\"}");
        Instant afterDone = expiresAt(user.id(), key);

        clock.advance(Duration.ofSeconds(30));
        idempotencyService.renewLeases();

        assertThat(expiresAt(user.id(), key)).as("hàng đã xong giữ hạn 24 giờ").isEqualTo(afterDone);
    }

    @Test
    void concurrentRequestsWithSameKey_createOnlyOnce_othersGet409OrReplay() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String key = UUID.randomUUID().toString();
        String json = "{\"name\": \"Đua khoá\"}";
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return postWithKey(user.accessToken(), PROJECTS, key, json).andReturn();
            }));
        }
        ready.await();
        go.countDown();

        int fresh = 0;
        for (Future<MvcResult> future : futures) {
            MockHttpServletResponse response = future.get(60, TimeUnit.SECONDS).getResponse();
            boolean replay = "true".equals(response.getHeader("Idempotent-Replay"));
            if (response.getStatus() == 201 && !replay) {
                fresh++;
            } else {
                assertThat(response.getStatus() == 201 && replay || response.getStatus() == 409)
                        .as("bản thua đua phải là 409 idempotency-in-progress hoặc phát lại; nhận %s", response.getStatus())
                        .isTrue();
            }
        }
        pool.shutdown();

        assertThat(fresh).as("đúng MỘT request thực sự xử lý").isEqualTo(1);
        assertThat(countProjectsNamed(user.id(), "Đua khoá")).isEqualTo(1);
        assertThat(keyRows(user.id(), key)).isEqualTo(1);
    }

    @Test
    void purgeExpired_deletesOnlyExpiredKeys_includingAbandonedInProgressRows() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        Instant now = clock.instant();
        insertKey(user.id(), "het-han", PROJECTS, "{}", 201, now.minus(Duration.ofHours(30)), now.minus(Duration.ofHours(6)));
        insertKey(user.id(), "bo-do", PROJECTS, "{}", null, now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(3)));
        insertKey(user.id(), "con-han", PROJECTS, "{}", 201, now, now.plus(Duration.ofHours(24)));

        idempotencyService.purgeExpired();

        assertThat(jdbcTemplate.queryForList("select idem_key from idempotency_keys where user_id = ?",
                String.class, user.id())).containsExactly("con-han");
    }
}
