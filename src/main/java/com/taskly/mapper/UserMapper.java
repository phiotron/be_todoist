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
                user.getLocale(), user.getTimeZone(), user.hasPassword(), user.linkedProviders());
    }
}
