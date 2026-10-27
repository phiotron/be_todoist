package com.taskly.dto.task;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.taskly.common.error.ProblemTexts;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Sửa task (PATCH, spec 12.3). version bắt buộc (optimistic lock, spec 5.6). content/description/priority: null =
 * không đổi; description "" = xoá mô tả.
 * dueDate / dueDatetime / durationMinutes / deadlineDate là Optional CÓ CHỦ ĐÍCH (quyết định Q7): vắng mặt trong
 * JSON → null = không đổi; gửi null → Optional.empty() = xoá. Gửi dueDate hoặc dueDatetime là THAY TOÀN BỘ due.
 */
public record UpdateTaskRequest(
        @NotNull(message = ProblemTexts.VERSION_REQUIRED)
        Long version,

        @Size(min = 1, max = 500, message = ProblemTexts.TASK_CONTENT_SIZE)
        String content,

        @Size(max = 16383, message = ProblemTexts.DESCRIPTION_SIZE)
        String description,

        @Min(value = 1, message = ProblemTexts.PRIORITY_RANGE)
        @Max(value = 4, message = ProblemTexts.PRIORITY_RANGE)
        Integer priority,

        Optional<LocalDate> dueDate,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
        Optional<LocalDateTime> dueDatetime,

        Optional<@Min(value = 1, message = ProblemTexts.DURATION_RANGE)
                 @Max(value = 1440, message = ProblemTexts.DURATION_RANGE) Integer> durationMinutes,

        Optional<LocalDate> deadlineDate,

        @Schema(description = "M2: vắng / null = giữ; [] = bỏ hết; mảng = thay toàn bộ") List<UUID> labelIds) {

    public UpdateTaskRequest {
        content = content == null ? null : content.strip();
    }

    /** true khi request có nhắc tới due (kể cả null để xoá). */
    @JsonIgnore
    @Schema(hidden = true)
    public boolean touchesDue() {
        return dueDate != null || dueDatetime != null;
    }
}
