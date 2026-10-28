package com.taskly.repository;

import com.taskly.dto.backup.BackupResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bảng backups + backup_runs (M11 F8) bằng JDBC: nội dung backup là JSON do Postgres dựng (jsonb, khoá sắp ổn định →
 * cùng dữ liệu cho cùng chuỗi, so hash được). Không có entity JPA: không ai sửa / đọc từng trường.
 */
@Repository
public class BackupRepository {

    /** Ảnh chụp dữ liệu của user (workspace user là thành viên + label của user); bỏ updated_at để chỉ so nội dung. */
    private static final String SNAPSHOT_SQL = """
            with ws as (select workspace_id from workspace_members where user_id = ?),
                 p as (select * from projects where workspace_id in (select workspace_id from ws)),
                 s as (select * from sections where project_id in (select id from p)),
                 t as (select * from tasks where project_id in (select id from p)),
                 l as (select * from labels where user_id = ?),
                 tl as (select task_id, label_id from task_labels where task_id in (select id from t))
            select jsonb_build_object(
                     'projects', coalesce((select jsonb_agg(to_jsonb(p) - 'updated_at' order by p.id) from p), '[]'::jsonb),
                     'sections', coalesce((select jsonb_agg(to_jsonb(s) - 'updated_at' order by s.id) from s), '[]'::jsonb),
                     'tasks', coalesce((select jsonb_agg(to_jsonb(t) - 'updated_at' order by t.id) from t), '[]'::jsonb),
                     'labels', coalesce((select jsonb_agg(to_jsonb(l) - 'updated_at' order by l.id) from l), '[]'::jsonb),
                     'taskLabels', coalesce((select jsonb_agg(to_jsonb(tl) order by tl.task_id, tl.label_id) from tl),
                                            '[]'::jsonb))::text as content,
                   (select count(*) from p) as project_count,
                   (select count(*) from t) as task_count
            """;

    /**
     * "Tới hạn": lần chạy trước (hoặc lúc tạo tài khoản nếu chưa chạy lần nào) TRƯỚC nửa đêm hôm nay theo users.time_zone.
     * date_trunc trên giờ địa phương rồi đổi ngược về timestamptz = thời điểm 00:00 hôm nay của người dùng.
     */
    private static final String DUE_CONDITION = "coalesce(r.last_run_at, u.created_at) < "
            + "(date_trunc('day', cast(? as timestamptz) at time zone u.time_zone) at time zone u.time_zone)";

    public record Snapshot(String content, int projectCount, int taskCount) {
    }

    private final JdbcTemplate jdbc;

    public BackupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> findDueUserIds(Instant now) {
        return jdbc.queryForList("select u.id from users u left join backup_runs r on r.user_id = u.id where "
                + DUE_CONDITION + " order by u.id", UUID.class, at(now));
    }

    public boolean isDue(UUID userId, Instant now) {
        Boolean due = jdbc.queryForObject("select exists (select 1 from users u left join backup_runs r "
                + "on r.user_id = u.id where u.id = ? and " + DUE_CONDITION + ")", Boolean.class, userId, at(now));
        return Boolean.TRUE.equals(due);
    }

    public Snapshot snapshot(UUID userId) {
        return jdbc.queryForObject(SNAPSHOT_SQL, (rs, row) -> new Snapshot(rs.getString("content"),
                rs.getInt("project_count"), rs.getInt("task_count")), userId, userId);
    }

    public Optional<String> lastHash(UUID userId) {
        return jdbc.queryForList("select last_hash from backup_runs where user_id = ?", String.class, userId).stream()
                .findFirst();
    }

    public void insert(UUID userId, Snapshot snapshot, String hash, long sizeBytes, Instant now) {
        jdbc.update("insert into backups (id, user_id, content, content_hash, size_bytes, project_count, task_count, "
                        + "created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, snapshot.content(), hash, sizeBytes, snapshot.projectCount(),
                snapshot.taskCount(), at(now), at(now));
    }

    /** Giữ `keep` bản mới nhất. */
    public int prune(UUID userId, int keep) {
        return jdbc.update("delete from backups where user_id = ? and id not in (select id from backups "
                + "where user_id = ? order by created_at desc, id desc limit ?)", userId, userId, keep);
    }

    public void recordRun(UUID userId, Instant now, String hash) {
        jdbc.update("insert into backup_runs (user_id, last_run_at, last_hash) values (?, ?, ?) "
                + "on conflict (user_id) do update set last_run_at = excluded.last_run_at, "
                + "last_hash = excluded.last_hash", userId, at(now), hash);
    }

    public List<BackupResponse> list(UUID userId) {
        return jdbc.query("select id, created_at, project_count, task_count, size_bytes from backups "
                        + "where user_id = ? order by created_at desc, id desc",
                (rs, row) -> new BackupResponse(rs.getObject("id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(), rs.getInt("project_count"),
                        rs.getInt("task_count"), rs.getLong("size_bytes")), userId);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
