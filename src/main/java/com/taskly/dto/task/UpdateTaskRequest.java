package com.taskly.dto.task;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Sửa task (PATCH, spec 12.3). version bắt buộc (optimistic lock, spec 5.6). content/description/priority: null =
 * không đổi; description "" = xoá mô tả.
 * dueDate / dueDatetime / durationMinutes / deadlineDate là Optional CÓ CHỦ ĐÍCH (quyết định Q7): vắng mặt trong
 * JSON → null = không đổi; gửi null → Optional.empty() = xoá. Gửi dueDate hoặc dueDatetime là THAY TOÀN BỘ due.
 */
public record UpdateTaskRequest(
        @NotNull(message = "{error.version-required}")
        Long version,

        @Size(min = 1, max = 500, message = "{error.task-content-size}")
        String content,

        @Size(max = 16383, message = "{error.description-size}")
        String description,

        @Min(value = 1, message = "{error.priority-range}")
        @Max(value = 4, message = "{error.priority-range}")
        Integer priority,

        Optional<LocalDate> dueDate,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
        Optional<LocalDateTime> dueDatetime,

        Optional<@Min(value = 1, message = "{error.duration-range}")
                 @Max(value = 1440, message = "{error.duration-range}") Integer> durationMinutes,

        Optional<LocalDate> deadlineDate) {

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
