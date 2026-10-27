package com.taskly.dto.task;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.taskly.common.error.ProblemTexts;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Tạo task (spec 12.3). id: UUID tuỳ chọn do client sinh (spec 12.11) — vắng thì server sinh; id không phải UUID →
 * Jackson báo 400. projectId null → dự án của cha (nếu có parentId), không thì Inbox (quyết định Q9).
 * parentId có giá trị → project / section phải khớp cha nếu có gửi kèm (khác → 400, dòng 3 của bảng quyết định),
 * vắng thì lấy theo cha. priority null → 4.
 * afterId là Optional CÓ CHỦ ĐÍCH (spec 12 đầu, I3): VẮNG trong JSON → component null = CUỐI vùng chứa;
 * "afterId": null → Optional.empty() = ĐẦU; có giá trị → ngay sau task đó.
 * dueDatetime là giờ FLOATING: nhập "yyyy-MM-dd'T'HH:mm[:ss]" (không Z / offset — có Z → Jackson báo 400; Q32), xuất
 * luôn "yyyy-MM-dd'T'HH:mm:ss" (TaskResponse). Có cả dueDate lẫn dueDatetime, hoặc duration mà không có giờ → 400
 * (kiểm ở TaskDueRules).
 */
public record CreateTaskRequest(
        UUID id,

        UUID projectId,

        UUID sectionId,

        UUID parentId,

        @NotNull(message = ProblemTexts.TASK_CONTENT_SIZE)
        @Size(min = 1, max = 500, message = ProblemTexts.TASK_CONTENT_SIZE)
        String content,

        @Size(max = 16383, message = ProblemTexts.DESCRIPTION_SIZE)
        String description,

        @Min(value = 1, message = ProblemTexts.PRIORITY_RANGE)
        @Max(value = 4, message = ProblemTexts.PRIORITY_RANGE)
        Integer priority,

        LocalDate dueDate,

        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm[:ss]")
        LocalDateTime dueDatetime,

        @Min(value = 1, message = ProblemTexts.DURATION_RANGE)
        @Max(value = 1440, message = ProblemTexts.DURATION_RANGE)
        Integer durationMinutes,

        LocalDate deadlineDate,

        Optional<UUID> afterId) {

    public CreateTaskRequest {
        content = content == null ? null : content.strip();
    }
}
