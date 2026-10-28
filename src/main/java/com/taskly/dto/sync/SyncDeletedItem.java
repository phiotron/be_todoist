package com.taskly.dto.sync;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Tombstone: bản ghi đã xoá vĩnh viễn. type = PROJECT, SECTION, TASK, LABEL, FILTER (mốc sau thêm). */
public record SyncDeletedItem(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id) {
}
