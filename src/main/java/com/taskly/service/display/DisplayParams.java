package com.taskly.service.display;

import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ApiException;
import com.taskly.entity.DueFilter;
import com.taskly.entity.GroupBy;
import com.taskly.entity.SortBy;
import com.taskly.entity.SortDirection;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Đọc tham số Display của GET tasks (chữ thường) → DisplayOptions đã kiểm theo view. */
public final class DisplayParams {

    private static final Pattern UUID_TEXT = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private DisplayParams() {
    }

    /** Không gửi tham số Display nào → empty (giữ hành vi M1). Thiếu phần nào lấy mặc định của view. */
    public static Optional<DisplayOptions> fromQuery(ViewKind kind, DisplayQuery query) {
        if (query == null || query.isEmpty()) {
            return Optional.empty();
        }
        DisplayOptions defaults = DisplayRules.defaults(kind);
        DisplayOptions options = new DisplayOptions(
                query.sort() == null ? defaults.sortBy() : parseEnum(SortBy.class, query.sort()),
                query.order() == null ? defaults.direction() : parseEnum(SortDirection.class, query.order()),
                query.group() == null ? defaults.groupBy() : parseEnum(GroupBy.class, query.group()),
                query.filterPriority() == null ? null : parsePriority(query.filterPriority()),
                query.filterLabelId() == null ? null : parseUuid(query.filterLabelId()),
                query.filterDue() == null ? null : parseEnum(DueFilter.class, query.filterDue()));
        DisplayRules.validate(kind, options);
        return Optional.of(options);
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (!value.equals(value.toLowerCase(Locale.ROOT))) {
            throw invalid();
        }
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
    }

    private static Integer parsePriority(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    /** Chỉ nhận đủ 8-4-4-4-4-12 chữ số hex ({@code UUID.fromString} còn nhận cả "1-1-1-1-1"). */
    private static UUID parseUuid(String value) {
        if (!UUID_TEXT.matcher(value).matches()) {
            throw invalid();
        }
        return UUID.fromString(value);
    }

    private static ApiException invalid() {
        return ApiException.badRequest(ErrorMessage.DISPLAY_INVALID);
    }
}
