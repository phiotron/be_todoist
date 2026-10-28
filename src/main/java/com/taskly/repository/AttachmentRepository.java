package com.taskly.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bảng comment_attachments (M8b, V12). Xoá hàng → trigger đưa storage_key vào hàng đợi dọn file. */
@Repository
public class AttachmentRepository {

    public record AttachmentRow(UUID id, UUID uploaderId, UUID commentId, String fileName, String contentType,
                                long sizeBytes, String storageKey, Instant createdAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public AttachmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(AttachmentRow row) {
        jdbc.update("""
                insert into comment_attachments (id, uploader_id, comment_id, file_name, content_type, size_bytes,
                                                 storage_key, created_at)
                values (:id, :uploaderId, null, :fileName, :contentType, :sizeBytes, :storageKey, :createdAt)
                """, new MapSqlParameterSource()
                .addValue("id", row.id()).addValue("uploaderId", row.uploaderId())
                .addValue("fileName", row.fileName()).addValue("contentType", row.contentType())
                .addValue("sizeBytes", row.sizeBytes()).addValue("storageKey", row.storageKey())
                .addValue("createdAt", Timestamp.from(row.createdAt())));
    }

    /** FOR UPDATE: hai comment cùng gắn một file → request sau thấy comment_id đã đặt. */
    public Optional<AttachmentRow> findForUpdate(UUID id) {
        return jdbc.query("select * from comment_attachments where id = :id for update",
                new MapSqlParameterSource("id", id), AttachmentRepository::map).stream().findFirst();
    }

    public Optional<AttachmentRow> find(UUID id) {
        return jdbc.query("select * from comment_attachments where id = :id",
                new MapSqlParameterSource("id", id), AttachmentRepository::map).stream().findFirst();
    }

    public void attach(UUID id, UUID commentId) {
        jdbc.update("update comment_attachments set comment_id = :commentId where id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("commentId", commentId));
    }

    /** File tải lên mà chưa gắn comment trước `before` → xoá hàng (trigger xếp file vào hàng đợi dọn). */
    public int deleteUnattachedBefore(Instant before) {
        return jdbc.update("delete from comment_attachments where comment_id is null and created_at < :before",
                new MapSqlParameterSource("before", Timestamp.from(before)));
    }

    private static AttachmentRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new AttachmentRow(rs.getObject("id", UUID.class), rs.getObject("uploader_id", UUID.class),
                rs.getObject("comment_id", UUID.class), rs.getString("file_name"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("storage_key"), CommentRepository.instant(rs, "created_at"));
    }
}
