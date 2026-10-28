package com.taskly.dto.sync;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kết quả POST sync. changes: khoá = loại tài nguyên (projects, sections, tasks, labels, filters — mốc sau thêm khoá),
 * giá trị = bản ghi đầy đủ như API REST (ProjectResponse, TaskResponse…). deleted: bản ghi đã xoá vĩnh viễn từ token cũ.
 * results: đúng thứ tự commands. tempIdMapping: tempId → id thật của mọi lệnh tạo thành công trong request.
 */
public record SyncResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String syncToken,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "true = changes là toàn bộ dữ liệu; app thay hẳn bản cục bộ")
        boolean fullSync,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, List<Object>> changes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SyncDeletedItem> deleted,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SyncCommandResult> results,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, UUID> tempIdMapping) {
}
