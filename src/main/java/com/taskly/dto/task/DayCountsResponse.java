package com.taskly.dto.task;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * GET tasks/day-counts (M6b, contract 8.2): số task đang mở theo ngày "yyyy-MM-dd" → số; MỌI ngày của khoảng đều có mặt
 * (0 khi không có task), theo thứ tự ngày tăng dần.
 */
public record DayCountsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Ngày yyyy-MM-dd → số task đang mở có due_day đó (mọi ngày của khoảng, 0 nếu không có)")
        Map<String, Integer> counts) {
}
