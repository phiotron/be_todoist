package com.taskly.dto.settings;

import com.taskly.entity.Appearance;
import com.taskly.entity.DateFormat;
import com.taskly.entity.SwipeAction;
import com.taskly.entity.Theme;
import com.taskly.entity.TimeFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Cài đặt theo tài khoản (spec bản 3 mục 12.1.1). Mọi field luôn có mặt → REQUIRED để types.gen.ts của mobile không sinh
 * kiểu optional. M4 thêm remindersEnabled, autoReminderMinutes ở cuối (cũng REQUIRED).
 */
public record SettingsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Theme theme,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Appearance appearance,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"vi", "en"}) String locale,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Múi giờ IANA, vd Asia/Ho_Chi_Minh")
        String timeZone,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "7",
                description = "Ngày bắt đầu tuần: 1 = Thứ Hai … 7 = Chủ nhật")
        int startDay,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "7",
                description = "\"Tuần sau\" = ngày này của tuần sau (1 = Thứ Hai)")
        int nextWeek,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "7",
                description = "\"Cuối tuần này\" = ngày này (6 = Thứ Bảy)")
        int weekendStartDay,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DateFormat dateFormat,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TimeFormat timeFormat,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "INBOX | TODAY | UPCOMING | project:<uuid> | label:<uuid> | filter:<uuid>")
        String startPage,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean smartDateRecognition,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SwipeAction swipeLeft,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SwipeAction swipeRight,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "3–5 khoá, cuối là BROWSE: INBOX, TODAY, UPCOMING, SEARCH, FILTERS_LABELS, BROWSE, "
                        + "tối đa một project:<uuid> | label:<uuid> | filter:<uuid>")
        List<String> navTabs,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Bật / tắt mọi nhắc việc trên máy (app áp dụng, server không lọc)")
        boolean remindersEnabled,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, minimum = "0", maximum = "1440",
                description = "Auto reminder: số phút trước giờ của task có giờ; null = tắt. "
                        + "Giá trị: 0, 5, 10, 15, 30, 45, 60, 120, 180, 1440")
        Integer autoReminderMinutes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "M6c: tự mở lại task con (≤ 30 con trực tiếp) khi hoàn thành task lặp")
        boolean resetSubtasks) {
}
