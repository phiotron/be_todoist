package com.taskly.dto.settings;

import com.taskly.entity.Theme;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cài đặt theo tài khoản (spec mục 12.1). Mọi field luôn có mặt → REQUIRED để types.gen.ts của mobile không sinh
 * kiểu optional. M4 thêm remindersEnabled, reminderOffsetMinutes, allDayReminderTime (cũng REQUIRED).
 */
public record SettingsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Theme theme,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"vi", "en"}) String locale,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Múi giờ IANA, vd Asia/Ho_Chi_Minh")
        String timeZone) {
}
