package com.taskly.dto.task;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;

/** Kết quả tasks/batch và tasks/overdue/reschedule: id mọi task bị đổi (gồm task lan truyền / xoá kèm). */
public record AffectedTasksResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> affectedTaskIds) {
}
