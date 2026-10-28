package com.taskly.security;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

/**
 * M8a: phạm vi đọc của một user = các dự án (projectIds) + workspace chứa chúng (workspaceIds). Truy vấn danh sách GIỮ
 * điều kiện cũ {@code t.workspace_id IN (:workspaceIds)} (chỉ mục ix_tasks_workspace_*) và THÊM
 * {@code t.project_id IN (:projectIds)} — người cộng tác chỉ thấy dự án được chia sẻ, không thấy dự án khác của chủ.
 * Hai danh sách không bao giờ rỗng lẫn nhau (workspaceIds rỗng ⇔ projectIds rỗng); caller kiểm isEmpty() trước khi
 * chạy câu SQL có IN (...).
 */
public record ReadScope(List<UUID> workspaceIds, List<UUID> projectIds) {

    public static final String WORKSPACE_IDS = "workspaceIds";
    public static final String PROJECT_IDS = "projectIds";

    public ReadScope {
        workspaceIds = List.copyOf(workspaceIds);
        projectIds = List.copyOf(projectIds);
    }

    public boolean isEmpty() {
        return projectIds.isEmpty();
    }

    /** Gắn :workspaceIds và :projectIds vào params (trả lại chính params). */
    public MapSqlParameterSource bind(MapSqlParameterSource params) {
        return params.addValue(WORKSPACE_IDS, workspaceIds).addValue(PROJECT_IDS, projectIds);
    }

    /** MapSqlParameterSource mới chỉ có :workspaceIds và :projectIds. */
    public MapSqlParameterSource params() {
        return bind(new MapSqlParameterSource());
    }
}
