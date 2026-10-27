package com.taskly.dto.task;

import com.taskly.common.error.ProblemTexts;
import jakarta.validation.constraints.NotNull;
import java.util.Optional;
import java.util.UUID;

/**
 * Chuyển task (cả cây con đi theo — spec 10.1). Về Inbox = projectId của Inbox. parentId có giá trị → section theo
 * cha; sectionId vắng = "không section".
 * afterId là Optional CÓ CHỦ ĐÍCH (spec 12 đầu, I3): VẮNG trong JSON → component null = CUỐI vùng chứa đích;
 * "afterId": null → Optional.empty() = ĐẦU vùng chứa; có giá trị → ngay sau task đó (phải thuộc vùng chứa đích).
 * TaskBatchService (Part 3) tạo request với afterId = null (= cuối).
 */
public record MoveTaskRequest(
        @NotNull(message = ProblemTexts.PROJECT_ID_REQUIRED)
        UUID projectId,

        UUID sectionId,

        UUID parentId,

        Optional<UUID> afterId) {
}
