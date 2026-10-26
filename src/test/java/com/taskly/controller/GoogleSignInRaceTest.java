package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.taskly.GoogleIntegrationTestBase;
import com.taskly.support.GoogleTestTokens;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Hai lần đăng nhập Google ĐẦU TIÊN cùng một sub chạy song song (vd bấm nút hai lần). Ràng buộc unique
 * (users.email, user_identities(provider, subject)) bảo đảm chỉ một tài khoản; request thua nhận 409 ProblemDetail
 * hoặc 200 với CÙNG tài khoản (nếu nó chạy sau khi request thắng đã commit). Lặp 5 vòng để tăng khả năng đua thật.
 */
class GoogleSignInRaceTest extends GoogleIntegrationTestBase {

    private static final int ROUNDS = 5;

    @Test
    void concurrentFirstSignInWithSameSubject_createsExactlyOneAccount() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                GoogleTestTokens.IdToken token = googleTokens.idToken();
                String idToken = token.sign();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<MvcResult>> calls = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    calls.add(executor.submit(() -> {
                        start.await();
                        return postGoogleSignIn(idToken).andReturn();
                    }));
                }
                start.countDown();

                Set<String> userIds = new HashSet<>();
                List<Integer> statuses = new ArrayList<>();
                for (Future<MvcResult> call : calls) {
                    MvcResult result = call.get(30, TimeUnit.SECONDS);
                    int status = result.getResponse().getStatus();
                    statuses.add(status);
                    if (status == 200) {
                        userIds.add(JsonPath.read(body(result), "$.user.id"));
                    } else {
                        assertThat(result.getResponse().getContentType())
                                .as("vòng %d: lỗi phải là ProblemDetail", round)
                                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    }
                }

                assertThat(statuses).as("vòng %d", round).contains(200).allMatch(s -> s == 200 || s == 409);
                assertThat(userIds).as("vòng %d: mọi 200 cùng một tài khoản", round).hasSize(1);
                UUID userId = UUID.fromString(userIds.iterator().next());
                assertThat(countUsersByEmail(token.getEmail())).isEqualTo(1);
                assertThat(countIdentities(token.getSubject())).isEqualTo(1);
                assertThat(jdbcTemplate.queryForObject(
                        "select count(*) from workspaces where owner_id = ?", Integer.class, userId)).isEqualTo(1);
            }
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
