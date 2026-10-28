package com.taskly.repository;

import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Hàng đợi dọn file stored_file_deletions (M8b, V12) — trigger ghi, StoredFileCleanupService đọc + xoá. */
@Repository
public class StoredFileDeletionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public StoredFileDeletionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<String> nextBatch(int limit) {
        return jdbc.queryForList("select storage_key from stored_file_deletions order by queued_at, storage_key limit :limit",
                new MapSqlParameterSource("limit", limit), String.class);
    }

    public void remove(String storageKey) {
        jdbc.update("delete from stored_file_deletions where storage_key = :key",
                new MapSqlParameterSource("key", storageKey));
    }

    /** Khoá này có đang được dùng (file đính kèm / ảnh đại diện) không — phòng khoá bị xếp hàng nhầm. */
    public boolean isReferenced(String storageKey) {
        Integer count = jdbc.queryForObject("""
                select (select count(*) from comment_attachments where storage_key = :key)
                     + (select count(*) from users where avatar_key = :key)
                """, new MapSqlParameterSource("key", storageKey), Integer.class);
        return count != null && count > 0;
    }
}
