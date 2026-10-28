package com.taskly.service.sync;

import java.util.List;
import java.util.UUID;

/**
 * ĐIỂM CẮM thứ hai (plan M9 mục 8.3): một loại thực thể trả trong "changes". Bảng của nó phải có trigger
 * sync_track('<entityType>', …) trong migration của mốc. SyncService gom mọi bean.
 */
public interface SyncEntitySource {

    /** entity_type trong sync_changes, vd "TASK". */
    String entityType();

    /** Khoá trong changes / resourceTypes, vd "tasks". */
    String changesKey();

    /** Response đầy đủ (như API REST) của các id còn đọc được; id mất quyền / không còn → bỏ qua. */
    List<?> load(UUID userId, List<UUID> ids);

    /** Toàn bộ dữ liệu cho full sync (task: chỉ task đang mở). */
    List<?> full(UUID userId);
}
