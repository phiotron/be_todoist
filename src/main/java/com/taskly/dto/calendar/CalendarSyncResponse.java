package com.taskly.dto.calendar;

import io.swagger.v3.oas.annotations.media.Schema;

/** Kết quả một lần đồng bộ (M13b): sự kiện tạo / sửa / xoá ở lịch "Taskly" và số task cập nhật ngược từ lịch. */
public record CalendarSyncResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int created,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int updated,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int deleted,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int pulled) {

    public static CalendarSyncResponse none() {
        return new CalendarSyncResponse(0, 0, 0, 0);
    }
}
