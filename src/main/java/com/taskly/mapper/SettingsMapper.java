package com.taskly.mapper;

import com.taskly.dto.settings.SettingsResponse;
import com.taskly.entity.User;

/** User → SettingsResponse (chỉ các cột cài đặt). M4 thêm 3 field nhắc việc ở đây. */
public final class SettingsMapper {

    private SettingsMapper() {
    }

    public static SettingsResponse toResponse(User user) {
        return new SettingsResponse(user.getTheme(), user.getLocale(), user.getTimeZone());
    }
}
