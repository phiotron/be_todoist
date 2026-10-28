package com.taskly.dto.task;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body TUỲ CHỌN của POST tasks/{id}/close (M6c, contract 8.1). Chỉ có nghĩa với task lặp:
 * forever = true → "Complete forever" (kết thúc chu kỳ, hoàn thành thật); resetSubtasks → "Complete & reset sub-tasks"
 * (vắng / null = theo me/settings.resetSubtasks). Vắng body = hoàn thành thường.
 */
public record CloseTaskRequest(
        @Schema(description = "M6c: kết thúc chu kỳ của task lặp (hoàn thành thật)") Boolean forever,
        @Schema(description = "M6c: mở lại task con trực tiếp (≤ 30) khi task lặp nhảy kỳ; vắng = theo cài đặt")
        Boolean resetSubtasks) {

    public boolean isForever() {
        return Boolean.TRUE.equals(forever);
    }
}
