package com.taskly.dto.common;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.function.Function;

/**
 * Một trang của danh sách phân trang con trỏ (mục 5.10): {items, nextCursor}; nextCursor = null là hết.
 * Lưu ý: KHÔNG nhầm với org.springframework.data.domain.Page.
 */
public record Page<T>(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<T> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "Con trỏ trang kế, gửi lại qua tham số cursor; null = hết dữ liệu") String nextCursor) {

    /**
     * rows = kết quả truy vấn lấy limit + 1 dòng. Có dòng thứ limit+1 → còn trang sau, con trỏ dựng từ phần tử
     * cuối của trang hiện tại.
     */
    public static <T> Page<T> fromLookahead(List<T> rows, int limit, Function<T, String> cursorOf) {
        if (rows.size() <= limit) {
            return new Page<>(List.copyOf(rows), null);
        }
        List<T> items = List.copyOf(rows.subList(0, limit));
        return new Page<>(items, cursorOf.apply(items.get(limit - 1)));
    }

    public <R> Page<R> map(Function<T, R> mapper) {
        return new Page<>(items.stream().map(mapper).toList(), nextCursor);
    }
}
