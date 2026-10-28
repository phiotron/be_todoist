package com.taskly.repository;

import com.taskly.security.ReadScope;
import com.taskly.common.web.CursorCodec;
import com.taskly.repository.listing.DisplaySql;
import com.taskly.repository.listing.FilterSqlTranslator;
import com.taskly.repository.listing.KeyKind;
import com.taskly.repository.listing.KeysetPager;
import com.taskly.repository.listing.SortKey;
import com.taskly.repository.listing.SubtreeOrder;
import com.taskly.service.display.DisplayOptions;
import com.taskly.service.display.ViewKind;
import com.taskly.service.filterquery.FilterNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SQL liệt kê task có Display (label, filter; Task 19: Today, Upcoming, dự án). Chỉ trả id + khoá; TaskResponse dựng
 * bởi TaskViewService. Không nối chuỗi người dùng — mọi giá trị là tham số có tên. Phạm vi quyền lọc bằng
 * {@code t.workspace_id IN (:workspaceIds)} (spec 3.1); join projects chỉ để lọc dự án lưu trữ và dựng khoá nhóm.
 */
@Repository
public class TaskListingRepository {

    /** Bối cảnh của người gọi: today / now = giờ floating theo múi giờ tài khoản. */
    public record Context(UUID userId, ReadScope readScope, LocalDate today, LocalDateTime now, String timeZone) {
    }

    /** Phạm vi: id = label / dự án; filter = cây đã parse; from / to = cửa sổ ngày (gồm hai đầu) của Upcoming (Task 19). */
    public record Scope(ViewKind kind, UUID id, FilterNode filter, LocalDate from, LocalDate to) {
    }

    private static final String FROM = """
            FROM tasks t
            JOIN projects p ON p.id = t.project_id
            LEFT JOIN projects pp ON pp.id = p.parent_id
            LEFT JOIN projects pg ON pg.id = pp.parent_id
            LEFT JOIN sections s ON s.id = t.section_id
            WHERE t.workspace_id IN (:workspaceIds)
              AND t.project_id IN (:projectIds)
            """;

    private final KeysetPager pager;
    private final NamedParameterJdbcTemplate jdbc;

    public TaskListingRepository(KeysetPager pager, NamedParameterJdbcTemplate jdbc) {
        this.pager = pager;
        this.jdbc = jdbc;
    }

    public List<SortKey> keys(Scope scope, DisplayOptions display) {
        return DisplaySql.keys(scope.kind(), display);
    }

    public List<KeysetPager.KeyedId> page(Context context, Scope scope, DisplayOptions display,
                                          CursorCodec.Cursor after, int limit) {
        MapSqlParameterSource params = context.readScope().params() // M8a: :workspaceIds + :projectIds
                .addValue("userId", context.userId())
                .addValue("today", context.today())
                .addValue("now", context.now())
                .addValue("tz", context.timeZone());
        StringBuilder where = new StringBuilder(FROM).append(scopeSql(context, scope, params));
        DisplaySql.appendFilters(display, context.today(), where, params);
        return pager.page("t.id", where.toString(), keys(scope, display), params, after, limit);
    }

    /** M7 view=calendar: khoá cố định due_sort → priority → id (không theo Display sort / group). */
    public static final List<SortKey> CALENDAR_KEYS = List.of(
            new SortKey("COALESCE(t.due_sort, TIMESTAMP '9999-12-31 00:00:00')", KeyKind.LOCAL_DATETIME, false),
            new SortKey("t.priority", KeyKind.INT, false),
            new SortKey("t.id", KeyKind.ID, false));

    /**
     * M7 view=calendar: task ĐANG MỞ, mọi cấp (cả sub-task), due_day ∈ [scope.from, scope.to]. scope.kind = null →
     * mọi dự án chưa lưu trữ; PROJECT → đúng dự án (dự án lưu trữ vẫn xem được, chỉ đọc); LABEL / FILTER → như
     * danh sách label / filter. Bộ lọc Display (priority / label / due) áp như các danh sách khác.
     */
    public List<KeysetPager.KeyedId> calendarPage(Context context, Scope scope, DisplayOptions display,
                                                  CursorCodec.Cursor after, int limit) {
        MapSqlParameterSource params = context.readScope().params() // M8a: :workspaceIds + :projectIds
                .addValue("userId", context.userId())
                .addValue("today", context.today())
                .addValue("now", context.now())
                .addValue("tz", context.timeZone())
                .addValue("calFrom", scope.from())
                .addValue("calTo", scope.to());
        StringBuilder where = new StringBuilder(FROM)
                .append(" AND t.completed_at IS NULL AND t.due_day BETWEEN :calFrom AND :calTo");
        if (scope.kind() == null) {
            where.append(" AND p.archived_at IS NULL");
        } else if (scope.kind() == ViewKind.PROJECT) {
            params.addValue("scopeProjectId", scope.id());
            where.append(" AND t.project_id = :scopeProjectId");
        } else {
            where.append(scopeSql(context, scope, params));
        }
        DisplaySql.appendFilters(display, context.today(), where, params);
        return pager.page("t.id", where.toString(), CALENDAR_KEYS, params, after, limit);
    }

    private static String scopeSql(Context context, Scope scope, MapSqlParameterSource params) {
        return switch (scope.kind()) {
            case LABEL -> {
                params.addValue("scopeLabelId", scope.id());
                yield " AND p.archived_at IS NULL AND t.completed_at IS NULL AND EXISTS (SELECT 1 FROM task_labels sl"
                        + " WHERE sl.task_id = t.id AND sl.label_id = :scopeLabelId)";
            }
            case FILTER -> {
                FilterSqlTranslator.Sql sql = FilterSqlTranslator.translate(scope.filter(),
                        new FilterSqlTranslator.Context(context.today(), context.now(), context.timeZone()));
                params.addValues(sql.params());
                yield " AND p.archived_at IS NULL AND t.completed_at IS NULL AND (" + sql.condition() + ")";
            }
            case TODAY -> " AND p.archived_at IS NULL AND t.completed_at IS NULL AND t.due_day <= :today";
            case UPCOMING -> { // scope.from / to = cửa sổ ngày đã kiểm (UpcomingWindow), gồm cả hai đầu
                params.addValue("upcomingFrom", scope.from()).addValue("upcomingTo", scope.to());
                yield " AND p.archived_at IS NULL AND t.completed_at IS NULL"
                        + " AND t.due_day BETWEEN :upcomingFrom AND :upcomingTo";
            }
            case PROJECT -> { // dự án lưu trữ vẫn xem được (chỉ đọc): không lọc archived_at
                params.addValue("scopeProjectId", scope.id());
                yield " AND t.project_id = :scopeProjectId AND t.parent_id IS NULL AND t.completed_at IS NULL";
            }
        };
    }

    /** Mọi con cháu (kể cả đã xong) của các gốc — SubtreeOrder.arrange xếp lại thứ tự hiển thị. */
    public List<SubtreeOrder.Node> descendants(Collection<UUID> rootIds) {
        if (rootIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                WITH RECURSIVE sub(id, parent_id, sort_order) AS (
                    SELECT c.id, c.parent_id, c.sort_order FROM tasks c WHERE c.parent_id IN (:rootIds)
                    UNION ALL
                    SELECT c.id, c.parent_id, c.sort_order FROM tasks c JOIN sub ON c.parent_id = sub.id)
                SELECT id, parent_id, sort_order FROM sub
                """, Map.of("rootIds", rootIds), (rs, rowNum) -> new SubtreeOrder.Node(
                rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class), rs.getLong("sort_order")));
    }
}
