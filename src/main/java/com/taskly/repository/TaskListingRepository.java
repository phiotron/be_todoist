package com.taskly.repository;

import com.taskly.common.web.CursorCodec;
import com.taskly.repository.listing.DisplaySql;
import com.taskly.repository.listing.FilterSqlTranslator;
import com.taskly.repository.listing.KeysetPager;
import com.taskly.repository.listing.SortKey;
import com.taskly.service.display.DisplayOptions;
import com.taskly.service.display.ViewKind;
import com.taskly.service.filterquery.FilterNode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Repository;

/**
 * SQL liệt kê task có Display (label, filter; Task 19: Today, Upcoming, dự án). Chỉ trả id + khoá; TaskResponse dựng
 * bởi TaskViewService. Không nối chuỗi người dùng — mọi giá trị là tham số có tên. Phạm vi quyền lọc bằng
 * {@code t.workspace_id IN (:workspaceIds)} (spec 3.1); join projects chỉ để lọc dự án lưu trữ và dựng khoá nhóm.
 */
@Repository
public class TaskListingRepository {

    /** Bối cảnh của người gọi: today / now = giờ floating theo múi giờ tài khoản. */
    public record Context(UUID userId, List<UUID> workspaceIds, LocalDate today, LocalDateTime now, String timeZone) {
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
            """;

    private final KeysetPager pager;

    public TaskListingRepository(KeysetPager pager) {
        this.pager = pager;
    }

    public List<SortKey> keys(Scope scope, DisplayOptions display) {
        return DisplaySql.keys(scope.kind(), display);
    }

    public List<KeysetPager.KeyedId> page(Context context, Scope scope, DisplayOptions display,
                                          CursorCodec.Cursor after, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("userId", context.userId())
                .addValue("workspaceIds", context.workspaceIds())
                .addValue("today", context.today())
                .addValue("now", context.now());
        StringBuilder where = new StringBuilder(FROM).append(scopeSql(context, scope, params));
        DisplaySql.appendFilters(display, context.today(), where, params);
        return pager.page("t.id", where.toString(), keys(scope, display), params, after, limit);
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
            default -> throw new IllegalArgumentException("Scope chưa hỗ trợ: " + scope.kind()); // Task 19 thay
        };
    }
}
