package com.taskly.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bảng sync_commands (V13): chống gửi trùng lệnh POST sync theo (user, uuid). claim() chạy TRONG transaction của lệnh:
 * INSERT … ON CONFLICT DO NOTHING — request song song cùng uuid chờ bên kia commit / rollback; lệnh lỗi rollback luôn hàng
 * này nên gửi lại được.
 */
@Repository
public class SyncCommandRepository {

    private final JdbcTemplate jdbcTemplate;

    public SyncCommandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** true = lần đầu (đã giữ chỗ); false = uuid đã chạy thành công trước đó. */
    public boolean claim(UUID userId, UUID commandUuid, String type, Instant now) {
        return jdbcTemplate.update("insert into sync_commands (user_id, command_uuid, command_type, result, created_at) "
                + "values (?, ?, ?, '{}', ?) on conflict (user_id, command_uuid) do nothing",
                userId, commandUuid, type, Timestamp.from(now)) == 1;
    }

    public void saveResult(UUID userId, UUID commandUuid, String resultJson) {
        jdbcTemplate.update("update sync_commands set result = ? where user_id = ? and command_uuid = ?",
                resultJson, userId, commandUuid);
    }

    public Optional<String> result(UUID userId, UUID commandUuid) {
        List<String> rows = jdbcTemplate.queryForList(
                "select result from sync_commands where user_id = ? and command_uuid = ?", String.class, userId, commandUuid);
        return rows.stream().findFirst();
    }

    public int purgeOlderThan(Instant cutoff) {
        return jdbcTemplate.update("delete from sync_commands where created_at < ?", Timestamp.from(cutoff));
    }
}
