package com.taskly.mapper;

import com.taskly.dto.user.UserResponse;
import com.taskly.entity.User;

/**
 * Entity → DTO viết tay; chỉ chép các trường được phép lộ ra ngoài.
 * Đọc user.linkedProviders() (LAZY) → chỉ gọi bên trong transaction với User đang managed hoặc vừa tạo.
 */
public final class UserMapper {

    private UserMapper() {
    }

    public static UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(),
                user.getLocale(), user.getTimeZone(), user.isTwoFactorEnabled(), user.hasPassword(),
                user.linkedProviders(), avatarUrl(user.getId(), user.getAvatarUpdatedAt()));
    }

    /** M8b: đường dẫn tương đối của ảnh đại diện; ?v= đổi mỗi lần thay ảnh để app không dùng ảnh cũ trong cache. */
    public static String avatarUrl(java.util.UUID userId, java.time.Instant avatarUpdatedAt) {
        return avatarUpdatedAt == null ? null
                : "/api/v1/users/" + userId + "/avatar?v=" + avatarUpdatedAt.toEpochMilli();
    }
}
