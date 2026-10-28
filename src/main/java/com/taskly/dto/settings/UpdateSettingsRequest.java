package com.taskly.dto.settings;

import com.taskly.common.validation.AutoReminderMinutes;
import com.taskly.common.validation.IanaTimeZone;
import com.taskly.common.validation.NavTabs;
import com.taskly.common.validation.StartPage;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Optional;

/**
 * PATCH /api/v1/me/settings (spec bản 3 mục 12.1.1): field nào vắng / null thì giữ nguyên. Giá trị ngoài danh sách →
 * 400 kèm errors[].field. Enum nhận chuỗi (không nhận kiểu enum) để giá trị lạ ra 400 có errors thay vì lỗi đọc JSON;
 * phân biệt hoa thường. M4 thêm remindersEnabled, autoReminderMinutes (tuỳ chọn). Ngoại lệ: autoReminderMinutes: null = tắt
 * auto reminder (spec 12.1.1).
 */
public record UpdateSettingsRequest(
        @Schema(allowableValues = {"TASKLY", "MOONSTONE", "TANGERINE", "KALE", "BLUEBERRY", "LAVENDER", "RASPBERRY"})
        @Pattern(regexp = "TASKLY|MOONSTONE|TANGERINE|KALE|BLUEBERRY|LAVENDER|RASPBERRY",
                message = "{validation.theme.invalid}")
        String theme,

        @Schema(allowableValues = {"AUTO", "LIGHT", "DARK"})
        @Pattern(regexp = "AUTO|LIGHT|DARK", message = "{validation.appearance.invalid}")
        String appearance,

        @Schema(allowableValues = {"vi", "en"})
        @Pattern(regexp = "vi|en", message = "{validation.locale.invalid}")
        String locale,

        @Schema(description = "Múi giờ IANA, vd Asia/Ho_Chi_Minh")
        @IanaTimeZone
        String timeZone,

        @Schema(minimum = "1", maximum = "7", description = "1 = Thứ Hai … 7 = Chủ nhật")
        @Min(value = 1, message = "{validation.day-of-week.range}")
        @Max(value = 7, message = "{validation.day-of-week.range}")
        Integer startDay,

        @Schema(minimum = "1", maximum = "7")
        @Min(value = 1, message = "{validation.day-of-week.range}")
        @Max(value = 7, message = "{validation.day-of-week.range}")
        Integer nextWeek,

        @Schema(minimum = "1", maximum = "7")
        @Min(value = 1, message = "{validation.day-of-week.range}")
        @Max(value = 7, message = "{validation.day-of-week.range}")
        Integer weekendStartDay,

        @Schema(allowableValues = {"DMY", "MDY"})
        @Pattern(regexp = "DMY|MDY", message = "{validation.date-format.invalid}")
        String dateFormat,

        @Schema(allowableValues = {"H24", "H12"})
        @Pattern(regexp = "H24|H12", message = "{validation.time-format.invalid}")
        String timeFormat,

        @Schema(description = "INBOX | TODAY | UPCOMING | project:<uuid> | label:<uuid> | filter:<uuid>")
        @StartPage
        String startPage,

        Boolean smartDateRecognition,

        @Schema(allowableValues = {"COMPLETE", "SCHEDULE", "DELETE", "REMINDERS", "SELECT", "NONE"})
        @Pattern(regexp = "COMPLETE|SCHEDULE|DELETE|REMINDERS|SELECT|NONE",
                message = "{validation.swipe-action.invalid}")
        String swipeLeft,

        @Schema(allowableValues = {"COMPLETE", "SCHEDULE", "DELETE", "REMINDERS", "SELECT", "NONE"})
        @Pattern(regexp = "COMPLETE|SCHEDULE|DELETE|REMINDERS|SELECT|NONE",
                message = "{validation.swipe-action.invalid}")
        String swipeRight,

        @Schema(description = "3–5 khoá khác nhau, BROWSE cuối, tối đa một mục ghim")
        @NavTabs
        List<String> navTabs,

        @Schema(description = "Bật / tắt mọi nhắc việc; vắng hoặc null = giữ nguyên")
        Boolean remindersEnabled,

        @Schema(nullable = true, minimum = "0", maximum = "1440",
                description = "Vắng = giữ nguyên; null = TẮT auto reminder. Giá trị: 0, 5, 10, 15, 30, 45, 60, 120, "
                        + "180, 1440")
        Optional<@AutoReminderMinutes Integer> autoReminderMinutes,

        @Schema(description = "M6c: Reset sub-tasks; vắng hoặc null = giữ nguyên")
        Boolean resetSubtasks) {
}
