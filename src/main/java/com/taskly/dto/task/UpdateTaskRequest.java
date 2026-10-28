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

        Optional<LocalDate> deadlineDate,

        @Schema(description = "M2: vắng / null = giữ; [] = bỏ hết; mảng = thay toàn bộ") List<UUID> labelIds,

        @Schema(description = "M6c: vắng = giữ (nhưng đổi due / lặp mà vắng thì xoá), null = xoá; ≤ 150 ký tự")
        Optional<@Size(max = 150, message = "{validation.due-string.size}") String> dueString,

        @Schema(description = "M6c: RRULE rút gọn; vắng = giữ, null = bỏ lặp (contract 8.1)")
        Optional<String> recurrenceRule,

        @Schema(description = "M6c: every! (vắng = giữ)")
        Boolean recurrenceFromCompletion,

        @Schema(description = "M6c: múi giờ cố định (IANA) của due có giờ; vắng = giữ, null = floating")
        Optional<String> dueTimezone) {

    public UpdateTaskRequest {
        content = content == null ? null : content.strip();
    }

    /** true khi request có nhắc tới due (kể cả null để xoá). */
    @JsonIgnore
    @Schema(hidden = true)
    public boolean touchesDue() {
        return dueDate != null || dueDatetime != null;
    }

    /** M6c: true khi request đổi quy tắc lặp (rule hoặc every!). */
    @JsonIgnore
    @Schema(hidden = true)
    public boolean touchesRecurrence() {
        return recurrenceRule != null || recurrenceFromCompletion != null;
    }
}
