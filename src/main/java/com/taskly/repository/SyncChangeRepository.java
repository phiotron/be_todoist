package com.taskly.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bảng sync_changes (V13, ghi bởi trigger sync_track / sync_bump). Token = xmin của snapshot hiện tại: mọi transaction có
 * xid < xmin đã kết thúc → đọc thay đổi theo xid ≥ token cũ không sót transaction commit muộn (có thể trả lặp — vô hại).
 */
@Repository
public class SyncChangeRepository {

    /** Một hàng thay đổi: bản ghi còn sống (deleted = false) hoặc tombstone. */
    public record ChangeRow(String entityType, UUID entityId, boolean deleted) {
    }

    private final JdbcTemplate jdbcTemplate;

    public SyncChangeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Gọi TRƯỚC khi đọc thay đổi (statement riêng, snapshot sau đó chỉ có thể mới hơn). */
    public long currentToken() {
        Long xmin = jdbcTemplate.queryForObject("select pg_snapshot_xmin(pg_current_snapshot())::text::bigint", Long.class);
        return xmin == null ? 0 : xmin;
    }

    /** Thay đổi có xid ≥ since trong phạm vi người dùng đọc được (workspace là thành viên + label / filter của mình). */
    public List<ChangeRow> changedSince(Collection<UUID> workspaceIds, UUID userId, long since) {
        return jdbcTemplate.query(con -> {
            var ps = con.prepareStatement("select entity_type, entity_id, deleted from sync_changes "
                    + "where xid >= ? and (workspace_id = any(?) or user_id = ?) order by xid, entity_type, entity_id");
            ps.setLong(1, since);
            ps.setArray(2, con.createArrayOf("uuid", workspaceIds.toArray()));
            ps.setObject(3, userId);
            return ps;
        }, (rs, i) -> new ChangeRow(rs.getString(1), rs.getObject(2, UUID.class), rs.getBoolean(3)));
    }

    public int purgeTombstonesBefore(Instant cutoff) {
        return jdbcTemplate.update("delete from sync_changes where deleted and changed_at < ?", Timestamp.from(cutoff));
    }
}
