package com.taskly.dto.project;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * PERM (quy tắc người dùng chốt 28/10, khác Todoist): người gọi được làm gì trong dự án — để app ẩn / vô hiệu nút. Dự
 * án nhóm: Admin đã tham gia → tất cả true; Member / Guest đã tham gia → chỉ canCompleteTasks; xem trước (chưa Join)
 * → tất cả false. Dự án cá nhân (kể cả được chia sẻ) → tất cả true. Theo VAI TRÒ — dự án lưu trữ vẫn chỉ đọc như cũ.
 */
public record ProjectCapabilities(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Tạo / sửa / xoá / chuyển / sao chép / sắp xếp task, đặt ngày, nhãn, nhắc...")
        boolean canEditTasks,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Đánh dấu hoàn thành / mở lại / Undo task")
        boolean canCompleteTasks,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Tạo / sửa / xoá / lưu trữ section")
        boolean canManageSections,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Bình luận / đính kèm / reaction")
        boolean canComment) {

    public static final ProjectCapabilities ALL = new ProjectCapabilities(true, true, true, true);
    public static final ProjectCapabilities NONE = new ProjectCapabilities(false, false, false, false);

    public static ProjectCapabilities of(boolean canEdit, boolean canComplete) {
        return new ProjectCapabilities(canEdit, canComplete, canEdit, canEdit);
    }
}
