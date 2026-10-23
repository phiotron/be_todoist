package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskly.IntegrationTestBase;
import com.taskly.dto.auth.RegisterRequest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Đăng ký là nguyên tử: một bước SAU khi đã ghi user/workspace/membership/Inbox thất bại thì
 * không được còn dòng nào. Gọi thẳng AuthService.register (không qua controller, không @Transactional
 * ở test — nếu không, transaction của test sẽ che mất commit/rollback thật) và dùng chung context.
 *
 * Cách gây lỗi muộn bằng dữ liệu thật: đặt "bây giờ" sát giới hạn năm 294276 của timestamptz của Postgres.
 * created_at của user/workspace/... còn hợp lệ, nhưng refresh_tokens.expires_at = now + 30 ngày vượt giới hạn
 * nên INSERT refresh token (câu lệnh cuối) lỗi, sau khi 4 INSERT trước đã chạy trong cùng transaction.
 */
class AuthServiceAtomicityTest extends IntegrationTestBase {

    private static final List<String> TABLES =
            List.of("users", "workspaces", "workspace_members", "projects", "refresh_tokens");

    @Autowired
    private AuthService authService;

    @Test
    void register_failureAfterEarlierStepsRollsBackEveryTable() {
        Instant nearPostgresMax = LocalDateTime.of(294276, 12, 10, 0, 0).toInstant(ZoneOffset.UTC);
        clock.advance(Duration.between(Instant.now(), nearPostgresMax));
        String email = uniqueEmail();
        List<Integer> before = TABLES.stream().map(this::count).toList();

        assertThatThrownBy(() -> authService.register(new RegisterRequest(email, FIXTURE_PASSWORD, "Rollback")))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from users where email = ?", Integer.class, email)).isZero();
        assertThat(TABLES.stream().map(this::count).toList())
                .as("số dòng của %s trước và sau khi đăng ký lỗi", TABLES)
                .isEqualTo(before);
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }
}
