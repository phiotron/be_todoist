package com.taskly.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bảng nối task_labels (không có entity JPA) + "chạm" task khi tập label đổi (tăng version, updated_at).
 * Mọi câu chỉ dùng tham số có tên.
 */
@Repository
public class TaskLabelRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public TaskLabelRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> labelIdsOf(UUID taskId) {
        return jdbc.queryForList("SELECT tl.label_id FROM task_labels tl JOIN labels l ON l.id = tl.label_id "
                + "WHERE tl.task_id = :taskId ORDER BY l.sort_order, l.id", Map.of("taskId", taskId), UUID.class);
    }

    public void insert(UUID taskId, Collection<UUID> labelIds) {
        for (UUID labelId : labelIds) {
            jdbc.update("INSERT INTO task_labels (task_id, label_id) VALUES (:taskId, :labelId) ON CONFLICT DO NOTHING",
                    Map.of("taskId", taskId, "labelId", labelId));
        }
    }

    public void replace(UUID taskId, Collection<UUID> labelIds) {
        jdbc.update("DELETE FROM task_labels WHERE task_id = :taskId", Map.of("taskId", taskId));
        insert(taskId, labelIds);
    }

    /** Thêm mọi cặp (task, label) chưa có; trả id các task thực sự được thêm label (giữ thứ tự gặp). */
    public Set<UUID> addToTasks(Collection<UUID> taskIds, Collection<UUID> labelIds) {
        if (taskIds.isEmpty() || labelIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        List<UUID> changed = jdbc.queryForList("INSERT INTO task_labels (task_id, label_id) "
                        + "SELECT t.id, l.id FROM unnest(ARRAY[:taskIds]::uuid[]) AS t(id) "
                        + "CROSS JOIN unnest(ARRAY[:labelIds]::uuid[]) AS l(id) "
                        + "ON CONFLICT DO NOTHING RETURNING task_id",
                new MapSqlParameterSource("taskIds", taskIds).addValue("labelIds", labelIds), UUID.class);
        return new LinkedHashSet<>(changed);
    }

    /** Bỏ các label khỏi các task; trả id task thực sự mất label. */
    public Set<UUID> removeFromTasks(Collection<UUID> taskIds, Collection<UUID> labelIds) {
        if (taskIds.isEmpty() || labelIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        List<UUID> changed = jdbc.queryForList("DELETE FROM task_labels WHERE task_id IN (:taskIds) "
                        + "AND label_id IN (:labelIds) RETURNING task_id",
                new MapSqlParameterSource("taskIds", taskIds).addValue("labelIds", labelIds), UUID.class);
        return new LinkedHashSet<>(changed);
    }

    public Map<UUID, Integer> countsFor(Collection<UUID> taskIds) {
        Map<UUID, Integer> counts = new HashMap<>();
        if (taskIds.isEmpty()) {
            return counts;
        }
        jdbc.query("SELECT task_id, count(*) AS n FROM task_labels WHERE task_id IN (:taskIds) GROUP BY task_id",
                Map.of("taskIds", taskIds),
                rs -> {
                    counts.put(rs.getObject("task_id", UUID.class), rs.getInt("n"));
                });
        return counts;
    }

    /** Chép label: mỗi cặp (id bản gốc → id bản sao). Bản sao phải đã có trong DB (flush trước). */
    public void copy(Map<UUID, UUID> originalToCopy) {
        originalToCopy.forEach((original, copy) -> jdbc.update("INSERT INTO task_labels (task_id, label_id) "
                + "SELECT :copy, label_id FROM task_labels WHERE task_id = :original",
                Map.of("copy", copy, "original", original)));
    }

    /** Đổi label = đổi dữ liệu task (spec 5.6, BR-10): version + 1, updated_at = now. */
    public void touchTasks(Collection<UUID> taskIds, Instant now) {
        if (taskIds.isEmpty()) {
            return;
        }
        jdbc.update("UPDATE tasks SET version = version + 1, updated_at = :now WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", taskIds).addValue("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC)));
    }
}
