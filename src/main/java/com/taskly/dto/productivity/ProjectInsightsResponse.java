package com.taskly.dto.productivity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Insights của dự án (GET /api/v1/projects/{id}/insights, M10 plan F6): task của CHÍNH dự án (gồm sub-task, không gồm dự
 * án con). progressPercent = completed * 100 / total làm tròn xuống (0 khi chưa có task); completedByDay = 14 ngày gần
 * nhất theo múi giờ tài khoản (cũ trước). Health / At risk (AI) không làm; Assigned → M8.
 */
public record ProjectInsightsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID projectId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int totalTasks,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int completedTasks,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int activeTasks,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int overdueTasks,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0", maximum = "100") int progressPercent,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DayCount> completedByDay) {

    public record DayCount(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate date,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int count) {
    }
}
