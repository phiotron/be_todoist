package com.taskly.service.display;

import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ApiException;
import com.taskly.entity.DueFilter;
import com.taskly.entity.GroupBy;
import com.taskly.entity.SortBy;
import com.taskly.entity.SortDirection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Bảng sort / group cho phép + mặc định theo view (spec 12.7). */
public final class DisplayRules {

    /**
     * Chiều của cờ khối Quá hạn (due_day &lt; hôm nay) ở Today khi {@code sort=date}: LUÔN tăng, không theo
     * {@code order}. Khối Quá hạn đứng đầu cả khi order=desc (spec 5.1 / 9.2 — khối Quá hạn ở đầu Today); order=desc
     * chỉ đảo khoá ngày-giờ bên trong từng khối.
     */
    public static final boolean OVERDUE_BLOCK_DESCENDING = false;

    private static final Map<ViewKind, Set<SortBy>> SORTS = new EnumMap<>(ViewKind.class);
    private static final Map<ViewKind, Set<GroupBy>> GROUPS = new EnumMap<>(ViewKind.class);

    static {
        Set<SortBy> dated = EnumSet.of(SortBy.DATE, SortBy.PRIORITY, SortBy.NAME, SortBy.ADDED, SortBy.DEADLINE);
        SORTS.put(ViewKind.TODAY, dated);
        SORTS.put(ViewKind.UPCOMING, dated);
        SORTS.put(ViewKind.PROJECT, EnumSet.allOf(SortBy.class));
        SORTS.put(ViewKind.LABEL, dated);
        SORTS.put(ViewKind.FILTER, dated);
        // M7: + ADDED (Grouping "Added date" của Todoist) ở mọi view trừ Upcoming (luôn nhóm theo ngày).
        GROUPS.put(ViewKind.TODAY, EnumSet.of(GroupBy.NONE, GroupBy.PRIORITY, GroupBy.PROJECT, GroupBy.LABEL,
                GroupBy.DEADLINE, GroupBy.ADDED));
        GROUPS.put(ViewKind.UPCOMING, EnumSet.of(GroupBy.NONE));
        GROUPS.put(ViewKind.PROJECT, EnumSet.of(GroupBy.NONE, GroupBy.DATE, GroupBy.DEADLINE, GroupBy.PRIORITY,
                GroupBy.LABEL, GroupBy.ADDED));
        GROUPS.put(ViewKind.LABEL, EnumSet.allOf(GroupBy.class));
        GROUPS.put(ViewKind.FILTER, EnumSet.allOf(GroupBy.class));
    }

    private DisplayRules() {
    }

    public static DisplayOptions defaults(ViewKind kind) {
        return new DisplayOptions(kind == ViewKind.PROJECT ? SortBy.MANUAL : SortBy.DATE, SortDirection.ASC,
                GroupBy.NONE, null, null, null);
    }

    public static void validate(ViewKind kind, DisplayOptions options) {
        boolean priorityOk = options.filterPriority() == null
                || (options.filterPriority() >= 1 && options.filterPriority() <= 4);
        if (options.sortBy() == null || options.direction() == null || options.groupBy() == null
                || !SORTS.get(kind).contains(options.sortBy()) || !GROUPS.get(kind).contains(options.groupBy())
                || !priorityOk || !dueAllowed(kind, options.filterDue())) {
            throw ApiException.badRequest(ErrorMessage.DISPLAY_INVALID);
        }
    }

    /**
     * filterDue theo view (spec 12.7 / 12.10, 18.1 m6): Today luôn có due → NO_DATE vô nghĩa (400); Upcoming chỉ có
     * task từ hôm nay → NO_DATE và OVERDUE luôn rỗng (400). OVERDUE ở Today theo isOverdue (spec 5.1).
     */
    static boolean dueAllowed(ViewKind kind, DueFilter due) {
        if (due == null) {
            return true;
        }
        return switch (kind) {
            case TODAY -> due != DueFilter.NO_DATE;
            case UPCOMING -> due != DueFilter.NO_DATE && due != DueFilter.OVERDUE;
            default -> true;
        };
    }

    /** Công tắc Completed tasks: dự án (M2), Today và Upcoming (M7, như Todoist); label / filter không có. */
    public static boolean allowsShowCompleted(ViewKind kind) {
        return kind == ViewKind.PROJECT || kind == ViewKind.TODAY || kind == ViewKind.UPCOMING;
    }

    /** M7: công tắc Future occurrences chỉ có ở Upcoming. */
    public static boolean allowsFutureOccurrences(ViewKind kind) {
        return kind == ViewKind.UPCOMING;
    }

    /**
     * Có đặt cờ khối Quá hạn (due_day &lt; hôm nay) làm khoá sắp đầu tiên không: chỉ Today với {@code sort=date}
     * (khớp thứ tự mặc định M1). Không phụ thuộc chiều sắp — xem {@link #OVERDUE_BLOCK_DESCENDING}.
     */
    public static boolean leadsWithOverdueBlock(ViewKind kind, DisplayOptions options) {
        return kind == ViewKind.TODAY && options.sortBy() == SortBy.DATE;
    }
}
