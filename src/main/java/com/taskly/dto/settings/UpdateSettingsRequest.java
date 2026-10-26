package com.taskly.dto.settings;

import com.taskly.common.validation.IanaTimeZone;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

/**
 * PATCH /api/v1/me/settings: field nào không gửi (null) thì giữ nguyên. Giá trị ngoài danh sách → 400 kèm
 * errors[].field. theme / locale nhận chuỗi (không nhận enum) để giá trị lạ ra 400 có errors thay vì lỗi đọc JSON.
 * M4 thêm remindersEnabled, reminderOffsetMinutes, allDayReminderTime (tuỳ chọn).
 */
public record UpdateSettingsRequest(
        @Schema(allowableValues = {"LIGHT", "DARK", "SYSTEM"})
        @Pattern(regexp = "LIGHT|DARK|SYSTEM", message = "{validation.theme.invalid}")
        String theme,

        @Schema(allowableValues = {"vi", "en"})
        @Pattern(regexp = "vi|en", message = "{validation.locale.invalid}")
        String locale,

        @Schema(description = "Múi giờ IANA, vd Asia/Ho_Chi_Minh")
        @IanaTimeZone
        String timeZone) {
}
