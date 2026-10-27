package com.taskly.service;

import java.util.List;
import java.util.UUID;

/**
 * Phát ĐỒNG BỘ trong transaction xoá dự án (gồm mọi dự án con) — trước lệnh DELETE. Listener (@EventListener) xoá
 * bản ghi đa hình không có khoá ngoại: M2 xoá view_options "project:<id>". Favorites do ProjectService xoá trực tiếp.
 */
public record ProjectsDeletedEvent(List<UUID> projectIds) {
}
