package com.taskly.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Đọc task để trả về client bằng SQL tay (một câu ra đủ dữ liệu của TaskResponse). Mọi truy vấn lọc theo
 * t.workspace_id IN (:workspaceIds) (phạm vi quyền từ AccessPolicy). Không còn xoá mềm / yêu thích task (bản 3). Task 21 thêm phân trang
 * keyset; M2 (tìm kiếm, lọc) tái dùng COLUMNS + FROM_WHERE + mapRow.
 */
@Repository
public class TaskViewRepository {

    /** Cột của mọi danh sách task (alias: t = tasks, p = projects, pt = task cha). */
    public static final String COLUMNS = """
            t.id, t.project_id, t.section_id, t.parent_id, t.content, t.description, t.priority,
            t.due_date, t.due_datetime, t.duration_minutes, t.deadline_date, t.due_string, t.completed_at,
            t.sort_order, t.version, t.created_at, t.updated_at, p.name as project_name, pt.content as parent_content,
            (select count(*) from tasks c where c.parent_id = t.id) as subtask_count,
            (select count(*) from tasks c where c.parent_id = t.id and c.completed_at is not null)
                    as completed_subtask_count
            """;

    /**
     * Nguồn + điều kiện chung. Cần tham số :workspaceIds (không rỗng). Lọc theo t.workspace_id (cột phi chuẩn của task,
     * có chỉ mục) — KHÔNG còn p.workspace_id (spec 10, 12.3). Nối thêm điều kiện bằng " and ...".
     */
    public static final String FROM_WHERE = """
            from tasks t
            join projects p on p.id = t.project_id
            left join tasks pt on pt.id = t.parent_id
            where t.workspace_id in (:workspaceIds)
            """;

    /** Câu SELECT gốc = "select " + COLUMNS + FROM_WHERE; nối thêm " and ...". */
    public static final String BASE_SELECT = "select " + COLUMNS + FROM_WHERE;

    /** RowMapper dùng chung cho mọi câu có đủ cột COLUMNS → TaskView. */
    public static final RowMapper<TaskView> ROW_MAPPER = (rs, rowNum) -> mapRow(rs);

    private final NamedParameterJdbcTemplate jdbc;

    public TaskViewRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<TaskView> findOne(List<UUID> workspaceIds, UUID taskId) {
        if (workspaceIds.isEmpty()) {
            return Optional.empty();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workspaceIds", workspaceIds)
                .addValue("taskId", taskId);
        return jdbc.query(BASE_SELECT + " and t.id = :taskId", params, ROW_MAPPER).stream().findFirst();
    }

    public static TaskView mapRow(ResultSet rs) throws SQLException {
        return new TaskView(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("section_id", UUID.class),
                rs.getObject("parent_id", UUID.class),
                rs.getString("content"),
                rs.getString("description"),
                rs.getInt("priority"),
                rs.getObject("due_date", LocalDate.class),
                rs.getObject("due_datetime", LocalDateTime.class),
                rs.getObject("duration_minutes", Integer.class),
                rs.getObject("deadline_date", LocalDate.class),
                rs.getString("due_string"),
                instant(rs, "completed_at"),
                rs.getInt("subtask_count"),
                rs.getInt("completed_subtask_count"),
                rs.getLong("sort_order"),
                rs.getLong("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getString("project_name"),
                rs.getString("parent_content"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
