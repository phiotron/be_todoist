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
import org.springframework.dao.DataAccessException;

/**
 * Đăng ký là nguyên tử: một bước SAU khi đã ghi user/workspace/membership/Inbox thất bại thì
 * không được còn dòng nào. Gọi thẳng AuthService.register (không qua controller, không @Transactional
 * ở test — nếu không, transaction của test sẽ che mất commit/rollback thật) và dùng chung context.
 *
 * Cách gây lỗi muộn bằng dữ liệu thật: đặt "bây giờ" sát giới hạn năm 294276 của timestamptz của Postgres.
 * created_at của user/workspace/... còn hợp lệ, nhưng refresh_tokens.expires_at = now + 30 ngày vượt giới hạn
 * nên INSERT refresh token (câu lệnh cuối) lỗi, sau khi 4 INSERT trước đã chạy trong cùng transaction.
 *
 * Giả định: {@code refresh-ttl} lớn hơn khoảng 22 ngày (mặc định 30 ngày). "Bây giờ" đặt ở 10/12/294276,
 * còn 21 ngày tới giới hạn 31/12/294276 của timestamptz; nếu refresh-ttl <= 21 ngày thì expires_at vẫn hợp lệ,
 * đăng ký thành công và test đỏ — khi đó phải dời mốc "bây giờ" sát giới hạn hơn.
 *
 * Kiểm "không còn dòng nào" bằng đếm dòng có created_at >= 1/1/294276 (chỉ dữ liệu do test này tạo, vì mọi test
 * khác dùng giờ thật) — không so số dòng toàn bảng, tránh nhiễu khi dữ liệu khác thay đổi.
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

        assertThatThrownBy(() -> authService.register(new RegisterRequest(email, FIXTURE_PASSWORD, "Rollback")))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("out of range");

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from users where email = ?", Integer.class, email)).isZero();
        for (String table : TABLES) {
            assertThat(countFarFutureRows(table))
                    .as("số dòng %s do đăng ký lỗi để lại (created_at >= năm 294276)", table)
                    .isZero();
        }
    }

    private int countFarFutureRows(String table) {
        return jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where created_at >= timestamptz '294276-01-01 00:00:00+00'",
                Integer.class);
    }
}
