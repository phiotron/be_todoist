package com.taskly.repository;

import com.taskly.common.web.CursorCodec;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Đọc task để trả về client bằng SQL tay (một câu ra đủ dữ liệu của TaskResponse). Mọi truy vấn lọc theo
 * t.workspace_id IN (:workspaceIds) (phạm vi quyền từ AccessPolicy). Không còn xoá mềm / yêu thích task (bản 3).
 * Phân trang keyset: (k0, k1, ..., id) so sánh bộ (row comparison) với giá trị khoá của dòng cuối trang trước → không
 * trùng, không sót. M2 (tìm kiếm, lọc, Display) tái dùng COLUMNS + FROM_WHERE + mapRow + page(); cột sinh t.due_day
 * (DATE) và t.due_sort (TIMESTAMP floating, khoá sắp theo due) dùng được làm khoá (KeyType.DATE / LOCAL_TIMESTAMP).
 */
@Repository
public class TaskViewRepository {

    /** Cột của mọi danh sách task (alias: t = tasks, p = projects, pt = task cha). */
    public static final String COLUMNS = """
            t.id, t.project_id, t.section_id, t.parent_id, t.content, t.description, t.priority,
            t.due_date, t.due_datetime, t.duration_minutes, t.deadline_date, t.due_string, t.completed_at,
            t.sort_order, t.version, t.created_at, t.updated_at, p.name as project_name, pt.content as parent_content,
            t.recurrence_rule, t.recurrence_from_completion, t.due_timezone,
            (select count(*) from tasks c where c.parent_id = t.id) as subtask_count,
            (select count(*) from tasks c where c.parent_id = t.id and c.completed_at is not null)
                    as completed_subtask_count,
            array(select tl.label_id from task_labels tl join labels l on l.id = tl.label_id
                  where tl.task_id = t.id order by l.sort_order, l.id) as label_ids,
            (select count(*) from comments cm where cm.task_id = t.id) as comment_count
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

    /** Kiểu của một cột khoá keyset: kiểu SQL để cast tham số con trỏ, cách đọc / ghi giá trị. */
    public enum KeyType {
        INT("integer"), LONG("bigint"), DATE("date"),
        /** timestamptz (completed_at, created_at). */
        TIMESTAMP("timestamptz"),
        /** timestamp KHÔNG múi giờ — giờ floating (due_sort = due_datetime hoặc due_date 00:00). */
        LOCAL_TIMESTAMP("timestamp"),
        /** uuid — khoá phá hoà (vd section_id khi hai section trùng sort_order). */
        ID("uuid");

        private final String sqlType;

        KeyType(String sqlType) {
            this.sqlType = sqlType;
        }

        Object param(CursorCodec.Cursor cursor, int index) {
            return switch (this) {
                case INT -> cursor.intValue(index);
                case LONG -> cursor.longValue(index);
                case DATE -> cursor.date(index);
                case TIMESTAMP -> OffsetDateTime.ofInstant(cursor.instant(index), ZoneOffset.UTC);
                case LOCAL_TIMESTAMP -> cursor.localDateTime(index);
                case ID -> cursor.uuid(index);
            };
        }

        String read(ResultSet rs, String column) throws SQLException {
            return switch (this) {
                case INT, LONG -> Long.toString(rs.getLong(column));
                case DATE -> rs.getObject(column, LocalDate.class).toString();
                case TIMESTAMP -> rs.getObject(column, OffsetDateTime.class).toInstant().toString();
                case LOCAL_TIMESTAMP -> rs.getObject(column, LocalDateTime.class).toString();
                case ID -> rs.getObject(column, UUID.class).toString();
            };
        }
    }

    /** Một cột khoá: biểu thức SQL (dùng alias t, p) + kiểu. Giá trị KHÔNG được null với các dòng thoả filter. */
    public record Key(String expression, KeyType type) {
    }

    /** Danh sách phân trang keyset: điều kiện lọc thêm + các khoá theo thứ tự + chiều (cùng chiều cho mọi khoá). */
    public record Keyset(String filter, List<Key> keys, boolean descending) {

        /** Số giá trị trong con trỏ = số khoá + id. */
        public int cursorSize() {
            return keys.size() + 1;
        }
    }

    /** Dòng + giá trị khoá (đã thành chuỗi, cuối cùng là id) để dựng con trỏ. */
    public record KeyedTaskView(TaskView view, List<String> keys) {
    }

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

    /** Các task theo id (trong phạm vi quyền). Thứ tự KHÔNG đảm bảo — caller tự sắp. */
    public List<TaskView> findByIds(List<UUID> workspaceIds, Collection<UUID> ids) {
        if (ids.isEmpty() || workspaceIds.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workspaceIds", workspaceIds)
                .addValue("ids", ids);
        return jdbc.query(BASE_SELECT + " and t.id in (:ids)", params, ROW_MAPPER);
    }

    /**
     * Một trang keyset. params phải có :workspaceIds và mọi tham số mà filter / key dùng.
     * after = con trỏ đã giải mã (null = trang đầu). limit = số dòng lấy (caller truyền pageSize + 1).
     */
    public List<KeyedTaskView> page(Keyset keyset, MapSqlParameterSource params, CursorCodec.Cursor after, int limit) {
        StringBuilder keyColumns = new StringBuilder();
        for (int i = 0; i < keyset.keys().size(); i++) {
            keyColumns.append(", (").append(keyset.keys().get(i).expression()).append(") as k").append(i);
        }
        StringBuilder sql = new StringBuilder("select * from (select ").append(COLUMNS).append(keyColumns)
                .append(' ').append(FROM_WHERE).append(" and (").append(keyset.filter()).append(")) x");
        if (after != null) {
            StringJoiner left = new StringJoiner(", ", "(", ")");
            StringJoiner right = new StringJoiner(", ", "(", ")");
            for (int i = 0; i < keyset.keys().size(); i++) {
                KeyType type = keyset.keys().get(i).type();
                left.add("x.k" + i);
                right.add("cast(:c" + i + " as " + type.sqlType + ")");
                params.addValue("c" + i, type.param(after, i));
            }
            left.add("x.id");
            right.add("cast(:cid as uuid)");
            params.addValue("cid", after.uuid(keyset.keys().size()));
            sql.append(" where ").append(left).append(keyset.descending() ? " < " : " > ").append(right);
        }
        String direction = keyset.descending() ? " desc" : "";
        StringJoiner order = new StringJoiner(", ", " order by ", "");
        for (int i = 0; i < keyset.keys().size(); i++) {
            order.add("x.k" + i + direction);
        }
        order.add("x.id" + direction);
        sql.append(order).append(" limit :limit");
        params.addValue("limit", limit);
        return jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            List<String> keys = new ArrayList<>();
            for (int i = 0; i < keyset.keys().size(); i++) {
                keys.add(keyset.keys().get(i).type().read(rs, "k" + i));
            }
            keys.add(rs.getObject("id", UUID.class).toString());
            return new KeyedTaskView(mapRow(rs), keys);
        });
    }

    /** Toàn bộ con cháu (kể cả đã xong) của các task gốc, mọi cấp. */
    public List<TaskView> findSubtrees(List<UUID> workspaceIds, Collection<UUID> rootIds) {
        if (rootIds.isEmpty() || workspaceIds.isEmpty()) {
            return List.of();
        }
        String sql = """
                with recursive tree(id) as (
                    select c.id from tasks c where c.parent_id in (:rootIds)
                    union all
                    select c.id from tasks c join tree on c.parent_id = tree.id
                )
                """ + BASE_SELECT + " and t.id in (select id from tree)";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workspaceIds", workspaceIds)
                .addValue("rootIds", rootIds);
        return jdbc.query(sql, params, ROW_MAPPER);
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
                rs.getString("parent_content"),
                labelIds(rs),
                rs.getString("recurrence_rule"),
                rs.getBoolean("recurrence_from_completion"),
                rs.getString("due_timezone"),
                rs.getInt("comment_count"));
    }

    static List<UUID> labelIds(ResultSet rs) throws SQLException {
        java.sql.Array array = rs.getArray("label_ids");
        return array == null ? List.of() : List.of((UUID[]) array.getArray());
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
