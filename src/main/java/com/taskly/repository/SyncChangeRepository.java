package com.taskly.repository;

import com.taskly.security.ReadScope;
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

    /**
     * Thay đổi có xid ≥ since trong phạm vi người dùng đọc được: label / filter của mình + bản ghi của DỰ ÁN đã tham gia
     * (M8a: ReadScope; V20 lưu project_id nên tombstone cũng lọc theo dự án). Dự án đã bị xoá hẳn (không còn hàng
     * projects để kiểm quyền) → chỉ thành viên workspace chứa nó nhận tombstone.
     */
    public List<ChangeRow> changedSince(ReadScope scope, UUID userId, long since) {
        return jdbcTemplate.query(con -> {
            var ps = con.prepareStatement("select c.entity_type, c.entity_id, c.deleted from sync_changes c "
                    + "where c.xid >= ? and (c.user_id = ? or (c.workspace_id = any(?) and (c.project_id = any(?) "
                    + "or (not exists (select 1 from projects p where p.id = c.project_id) and c.workspace_id in "
                    + "(select wm.workspace_id from workspace_members wm where wm.user_id = ?))))) "
                    + "order by c.xid, c.entity_type, c.entity_id");
            ps.setLong(1, since);
            ps.setObject(2, userId);
            ps.setArray(3, con.createArrayOf("uuid", scope.workspaceIds().toArray()));
            ps.setArray(4, con.createArrayOf("uuid", scope.projectIds().toArray()));
            ps.setObject(5, userId);
            return ps;
        }, (rs, i) -> new ChangeRow(rs.getString(1), rs.getObject(2, UUID.class), rs.getBoolean(3)));
    }

    public int purgeTombstonesBefore(Instant cutoff) {
        return jdbcTemplate.update("delete from sync_changes where deleted and changed_at < ?", Timestamp.from(cutoff));
    }
}
