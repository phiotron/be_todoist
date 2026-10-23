package com.taskly.dto.user;

import java.util.UUID;

/** Thông tin user trả cho client — KHÔNG có passwordHash. */
public record UserResponse(UUID id, String email, String displayName, String locale, String timeZone) {
}
