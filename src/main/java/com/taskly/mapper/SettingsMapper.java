package com.taskly.mapper;

import com.taskly.dto.settings.SettingsResponse;
import com.taskly.entity.User;

/** User → SettingsResponse (chỉ các cột cài đặt, spec bản 3 mục 12.1.1). M4 thêm 2 field nhắc việc ở cuối. */
public final class SettingsMapper {

    private SettingsMapper() {
    }

    public static SettingsResponse toResponse(User user) {
        return new SettingsResponse(
                user.getTheme(),
                user.getAppearance(),
                user.getLocale(),
                user.getTimeZone(),
                user.getStartDay(),
                user.getNextWeek(),
                user.getWeekendStartDay(),
                user.getDateFormat(),
                user.getTimeFormat(),
                user.getStartPage(),
                user.isSmartDateRecognition(),
                user.getSwipeLeft(),
                user.getSwipeRight(),
                user.getNavTabs(),
                user.isRemindersEnabled(),
                user.getAutoReminderMinutes(),
                user.getAddButtonPlacement(),
                user.isSyncHomeView(),
                user.isNavMenuShowTaskCount(),
                user.getNavMenuItems());
    }
}
