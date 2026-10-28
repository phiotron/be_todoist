package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M11 F8 — backup hằng ngày: tới hạn khi lần chạy trước (hoặc lúc tạo tài khoản) trước nửa đêm hôm nay theo múi giờ
 * tài khoản; chỉ tạo bản mới khi nội dung đổi; giữ 21 bản (LimitKind.BACKUPS). Job tắt trong test → gọi thẳng service.
 */
class BackupServiceTest extends IntegrationTestBase {

    /** 10:00 giờ Việt Nam ngày 23/10/2026. */
    private static final Instant MORNING = Instant.parse("2026-10-23T03:00:00Z");

    @Autowired
    private BackupService backupService;

    private RegisteredUser user;

    @BeforeEach
    void data() throws Exception {
        clock.freezeAt(MORNING);
        user = registerUser(uniqueEmail());
        testData.setTimeZone(user.id(), "Asia/Ho_Chi_Minh");
    }

    private int backups() {
        return jdbcTemplate.queryForObject("select count(*) from backups where user_id = ?", Integer.class, user.id());
    }

    /** Lúc 00:05 giờ Việt Nam của ngày thứ n sau MORNING. */
    private Instant justAfterMidnight(int days) {
        return Instant.parse("2026-10-23T17:05:00Z").plus(Duration.ofDays(days - 1L));
    }

    @Test
    void notDueBeforeLocalMidnight_dueRightAfter() {
        assertThat(backupService.isDue(user.id(), MORNING.plus(Duration.ofHours(13)))).isFalse(); // 23:00 VN
        assertThat(backupService.findDueUserIds(MORNING.plus(Duration.ofHours(13)))).doesNotContain(user.id());
        Instant midnight = justAfterMidnight(1);
        assertThat(backupService.isDue(user.id(), midnight)).isTrue();
        assertThat(backupService.findDueUserIds(midnight)).contains(user.id());
    }

    @Test
    void dueFollowsAccountTimeZone() {
        // tài khoản tạo lúc 22/10 23:00 New York; 23/10 17:05Z = 23/10 13:05 New York → đã qua nửa đêm 23/10 ở New York
        testData.setTimeZone(user.id(), "America/New_York");
        assertThat(backupService.isDue(user.id(), justAfterMidnight(1))).isTrue();
        assertThat(backupService.isDue(user.id(), Instant.parse("2026-10-23T03:59:00Z"))).isFalse(); // 23:59 New York
        testData.setTimeZone(user.id(), "Pacific/Kiritimati"); // UTC+14: 23/10 03:00Z = 23/10 17:00 → nửa đêm 24 = 23/10 10:00Z
        assertThat(backupService.isDue(user.id(), Instant.parse("2026-10-23T09:59:00Z"))).isFalse();
        assertThat(backupService.isDue(user.id(), Instant.parse("2026-10-23T10:01:00Z"))).isTrue();
    }

    @Test
    void createsBackupOnlyWhenContentChanged() {
        assertThat(backupService.backupIfDue(user.id(), justAfterMidnight(1))).isTrue();
        assertThat(backups()).isOne();
        // cùng ngày: không tới hạn nữa
        assertThat(backupService.isDue(user.id(), justAfterMidnight(1).plus(Duration.ofHours(1)))).isFalse();
        assertThat(backupService.backupIfDue(user.id(), justAfterMidnight(1).plus(Duration.ofHours(1)))).isFalse();
        // ngày sau, không đổi gì: có chạy nhưng không tạo bản mới
        assertThat(backupService.backupIfDue(user.id(), justAfterMidnight(2))).isFalse();
        assertThat(backups()).isOne();
        // đổi dữ liệu → ngày sau có bản mới
        testData.project(user.id(), "Mới");
        assertThat(backupService.backupIfDue(user.id(), justAfterMidnight(3))).isTrue();
        assertThat(backups()).isEqualTo(2);
        int projects = jdbcTemplate.queryForObject("select project_count from backups where user_id = ? "
                + "order by created_at desc limit 1", Integer.class, user.id());
        assertThat(projects).isEqualTo(2); // Inbox + Mới
    }

    @Test
    void keepsOnlyNewestBackups() {
        usageLimits.overrideMax(LimitKind.BACKUPS, 2);
        for (int day = 1; day <= 4; day++) {
            testData.project(user.id(), "Ngày " + day);
            assertThat(backupService.backupIfDue(user.id(), justAfterMidnight(day))).isTrue();
        }
        assertThat(backups()).isEqualTo(2);
        Instant oldest = jdbcTemplate.queryForObject("select min(created_at) from backups where user_id = ?",
                java.sql.Timestamp.class, user.id()).toInstant();
        assertThat(oldest).isEqualTo(justAfterMidnight(3));
    }

    @Test
    void backupContainsOnlyOwnData_andApiListsNewestFirst() throws Exception {
        RegisteredUser other = registerUser(uniqueEmail());
        testData.project(other.id(), "Bí mật");
        testData.project(user.id(), "Của tôi");
        backupService.backupIfDue(user.id(), justAfterMidnight(1));
        testData.project(user.id(), "Thứ hai");
        backupService.backupIfDue(user.id(), justAfterMidnight(2));
        String content = jdbcTemplate.queryForObject("select content from backups where user_id = ? "
                + "order by created_at desc limit 1", String.class, user.id());
        assertThat(content).contains("Của tôi").doesNotContain("Bí mật");

        authGet(user.accessToken(), "/api/v1/backups")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].createdAt").value(justAfterMidnight(2).toString()))
                .andExpect(jsonPath("$[0].projectCount").value(3))
                .andExpect(jsonPath("$[0].sizeBytes").isNumber())
                .andExpect(jsonPath("$[1].createdAt").value(justAfterMidnight(1).toString()));
        authGet(other.accessToken(), "/api/v1/backups").andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void runDue_processesDueUsers() {
        UUID id = user.id();
        int created = backupService.runDue(justAfterMidnight(1), java.util.List.of(id));
        assertThat(created).isOne();
        assertThat(backups()).isOne();
    }
}
