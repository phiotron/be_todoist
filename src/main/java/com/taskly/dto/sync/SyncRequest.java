package com.taskly.dto.sync;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * POST sync (M9, plan F2 / F3). syncToken vắng / null / "*" = đồng bộ đầy đủ. commands tối đa 100, chạy theo thứ tự.
 * resourceTypes vắng = mọi loại; [] = không lấy thay đổi (chỉ token mới); loại lạ → 400.
 */
public record SyncRequest(
        @Schema(nullable = true, description = "Token của lần đồng bộ trước; null / \"*\" = đầy đủ")
        String syncToken,

        @Size(max = 100, message = "{error.sync-commands-size}")
        List<@Valid SyncCommand> commands,

        @Schema(nullable = true, description = "projects, sections, tasks, labels, filters (vắng = tất cả)")
        List<String> resourceTypes) {
}
