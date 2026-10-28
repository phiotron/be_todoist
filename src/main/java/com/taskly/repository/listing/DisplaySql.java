package com.taskly.repository.listing;

import com.taskly.entity.DueFilter;
import com.taskly.entity.GroupBy;
import com.taskly.entity.SortDirection;
import com.taskly.service.display.DisplayOptions;
import com.taskly.service.display.DisplayRules;
import com.taskly.service.display.ViewKind;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * Khoá sắp / nhóm và điều kiện lọc của Display (spec 12.7). Alias: t = tasks, p = projects, pp = cha của p,
 * pg = ông của p, s = sections. Tham số có sẵn từ TaskListingRepository: :today (DATE), :now (TIMESTAMP floating),
 * :tz (id múi giờ tài khoản, M7).
 * Mọi khoá KHÔNG null; khoá cuối luôn là t.id.
 */
public final class DisplaySql {

    /** Biên ngày (gồm cả hai đầu) của DueFilter. */
    public record DueRange(LocalDate from, LocalDate to) {
    }

    private static final String FAR_DATE = "DATE '9999-12-31'";
    private static final String ZERO_UUID = "CAST('00000000-0000-0000-0000-000000000000' AS uuid)";
    /**
     * isOverdue (spec 5.1): task có giờ → due_datetime < bây giờ; chỉ ngày → due_date < hôm nay (giờ floating theo múi
     * giờ tài khoản). Dùng cho filterDue=overdue. KHÁC khối Quá hạn bên dưới.
     */
    public static final String IS_OVERDUE = "(t.due_datetime < :now OR t.due_date < :today)";
    /**
     * Khối Quá hạn của Today (spec 5.1, 18.1 C3): due_day < hôm nay. Task có giờ đã qua TRONG hôm nay nằm ở khối Hôm nay
     * (chữ đỏ), không ở đây. Cờ đầu của sort=date ở Today → thứ tự trùng mặc định M1 (A24).
     */
    public static final String OVERDUE_BLOCK = "t.due_day < :today";
    /** Khoá ngày-giờ floating = cột sinh {@code tasks.due_sort} của V2 (cùng khoá với view M1); không due → cuối. */
    private static final String DUE_KEY = "COALESCE(t.due_sort, TIMESTAMP '9999-12-31 00:00:00')";
    /** Đường dẫn sort_order từ dự án gốc tới dự án của task (≤ 3 cấp): cha đứng trước con. */
    private static final String PROJECT_PATH = "CASE WHEN pg.id IS NOT NULL THEN ARRAY[pg.sort_order, pp.sort_order,"
            + " p.sort_order] WHEN pp.id IS NOT NULL THEN ARRAY[pp.sort_order, p.sort_order]"
            + " ELSE ARRAY[p.sort_order] END";
    private static final String FIRST_LABEL = "(SELECT %s FROM task_labels fl JOIN labels l ON l.id = fl.label_id"
            + " WHERE fl.task_id = t.id ORDER BY l.sort_order, l.id LIMIT 1)";
    private static final String FIRST_LABEL_ORDER =
            "COALESCE(" + FIRST_LABEL.formatted("l.sort_order") + ", 9223372036854775807)";
    private static final String FIRST_LABEL_ID = "COALESCE(" + FIRST_LABEL.formatted("l.id") + ", " + ZERO_UUID + ")";
    /** M7 group=added: ngày tạo theo múi giờ tài khoản (tham số :tz = id IANA do TaskListingRepository đặt). */
    public static final String ADDED_DAY = "CAST((t.created_at AT TIME ZONE CAST(:tz AS text)) AS date)";

    private DisplaySql() {
    }

    public static List<SortKey> keys(ViewKind kind, DisplayOptions options) {
        List<SortKey> keys = new ArrayList<>(groupKeys(kind, options.groupBy()));
        keys.addAll(sortKeys(kind, options));
        keys.add(new SortKey("t.id", KeyKind.ID, false));
        return keys;
    }

    private static List<SortKey> groupKeys(ViewKind kind, GroupBy group) {
        if (kind == ViewKind.UPCOMING) {
            return List.of(new SortKey("t.due_day", KeyKind.DATE, false)); // Upcoming luôn nhóm theo ngày trước
        }
        return switch (group) {
            case NONE -> kind == ViewKind.PROJECT
                    ? List.of(new SortKey("CASE WHEN t.section_id IS NULL THEN 0 ELSE 1 END", KeyKind.INT, false),
                            new SortKey("COALESCE(s.sort_order, 0)", KeyKind.LONG, false),
                            new SortKey("COALESCE(t.section_id, " + ZERO_UUID + ")", KeyKind.ID, false))
                    : List.of();
            case DATE -> List.of(new SortKey("COALESCE(t.due_day, " + FAR_DATE + ")", KeyKind.DATE, false));
            case DEADLINE -> List.of(new SortKey("COALESCE(t.deadline_date, " + FAR_DATE + ")", KeyKind.DATE, false));
            case PRIORITY -> List.of(new SortKey("t.priority", KeyKind.INT, false));
            case PROJECT -> List.of(new SortKey(PROJECT_PATH, KeyKind.LONG_ARRAY, false),
                    new SortKey("p.id", KeyKind.ID, false));
            case LABEL -> List.of(new SortKey(FIRST_LABEL_ORDER, KeyKind.LONG, false),
                    new SortKey(FIRST_LABEL_ID, KeyKind.ID, false));
            case ADDED -> List.of(new SortKey(ADDED_DAY, KeyKind.DATE, true)); // M7: ngày mới trước
        };
    }

    private static List<SortKey> sortKeys(ViewKind kind, DisplayOptions options) {
        boolean desc = options.direction() == SortDirection.DESC;
        return switch (options.sortBy()) {
            case MANUAL -> List.of(new SortKey("t.sort_order", KeyKind.LONG, desc));
            case DATE -> dateKeys(kind, options, desc);
            case DEADLINE -> List.of(
                    new SortKey("CASE WHEN t.deadline_date IS NULL THEN 1 ELSE 0 END", KeyKind.INT, false),
                    new SortKey("COALESCE(t.deadline_date, " + FAR_DATE + ")", KeyKind.DATE, desc));
            case PRIORITY -> List.of(new SortKey("t.priority", KeyKind.INT, desc));
            case NAME -> List.of(new SortKey("f_unaccent(lower(t.content))", KeyKind.TEXT, desc));
            case ADDED -> List.of(new SortKey("t.created_at", KeyKind.INSTANT, desc));
        };
    }

    private static List<SortKey> dateKeys(ViewKind kind, DisplayOptions options, boolean desc) {
        List<SortKey> keys = new ArrayList<>();
        if (DisplayRules.leadsWithOverdueBlock(kind, options)) {
            // Khối Quá hạn luôn đứng đầu Today, KHÔNG đảo theo order (spec 3.1); order chỉ đảo khoá ngày-giờ trong khối.
            keys.add(new SortKey("CASE WHEN " + OVERDUE_BLOCK + " THEN 0 ELSE 1 END", KeyKind.INT,
                    DisplayRules.OVERDUE_BLOCK_DESCENDING));
        }
        keys.add(new SortKey("CASE WHEN t.due_day IS NULL THEN 1 ELSE 0 END", KeyKind.INT, false));
        keys.add(new SortKey(DUE_KEY, KeyKind.LOCAL_DATETIME, desc));
        if (kind == ViewKind.TODAY || kind == ViewKind.UPCOMING) { // khớp thứ tự mặc định của M1
            keys.add(new SortKey("t.priority", KeyKind.INT, false));
            keys.add(new SortKey(PROJECT_PATH, KeyKind.LONG_ARRAY, false));
            keys.add(new SortKey("t.sort_order", KeyKind.LONG, false));
        }
        return keys;
    }

    /** Thêm điều kiện lọc Display vào where (đã có WHERE) + tham số :dpPriority, :dpLabel, :dpFrom, :dpTo. */
    public static void appendFilters(DisplayOptions options, LocalDate today, StringBuilder where,
                                     MapSqlParameterSource params) {
        if (options.filterPriority() != null) {
            where.append(" AND t.priority = :dpPriority");
            params.addValue("dpPriority", options.filterPriority());
        }
        if (options.filterLabelId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM task_labels dtl WHERE dtl.task_id = t.id"
                    + " AND dtl.label_id = :dpLabel)");
            params.addValue("dpLabel", options.filterLabelId());
        }
        DueFilter due = options.filterDue();
        if (due == DueFilter.NO_DATE) {
            where.append(" AND t.due_day IS NULL");
        } else if (due == DueFilter.OVERDUE) {
            where.append(" AND ").append(IS_OVERDUE);
        } else if (due != null) {
            DueRange range = range(due, today);
            where.append(" AND t.due_day BETWEEN :dpFrom AND :dpTo");
            params.addValue("dpFrom", range.from()).addValue("dpTo", range.to());
        }
    }

    /** Biên của các DueFilter có khoảng ngày (NO_DATE / OVERDUE không dùng). Tuần bắt đầu Thứ Hai. */
    public static DueRange range(DueFilter filter, LocalDate today) {
        return switch (filter) {
            case TODAY -> new DueRange(today, today);
            case THIS_WEEK -> new DueRange(today, today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)));
            case NEXT_7_DAYS -> new DueRange(today, today.plusDays(6));
            case THIS_MONTH -> new DueRange(today, today.with(TemporalAdjusters.lastDayOfMonth()));
            case NEXT_30_DAYS -> new DueRange(today, today.plusDays(29));
            case NO_DATE, OVERDUE -> throw new IllegalArgumentException("Không có khoảng ngày: " + filter);
        };
    }
}
