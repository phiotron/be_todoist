package com.taskly.dto.settings;

import com.taskly.common.validation.AutoReminderMinutes;
import com.taskly.common.validation.DaysOff;
import com.taskly.common.validation.IanaTimeZone;
import com.taskly.common.validation.NavMenuItems;
import com.taskly.common.validation.NavTabs;
import com.taskly.common.validation.QuickAddChips;
import com.taskly.common.validation.SnoozeMinutes;
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

        // M3x (điều hướng): vắng / null = giữ nguyên.
        @Schema(allowableValues = {"LEFT", "CENTER", "RIGHT"})
        @Pattern(regexp = "LEFT|CENTER|RIGHT", message = "{validation.add-button-placement.invalid}")
        String addButtonPlacement,

        Boolean syncHomeView,

        Boolean navMenuShowTaskCount,

        @Schema(description = "Mục Navigation menu đang hiện theo thứ tự, không trùng: INBOX, TODAY, UPCOMING, "
                + "FILTERS_LABELS, REPORTING, BROWSE_TEMPLATES; [] = ẩn hết")
        @NavMenuItems
        List<String> navMenuItems,
        @Schema(description = "M6b: chip Quick Add theo thứ tự hiển thị, vắng = ẩn; PROJECT luôn có, không trùng. "
                + "Chip: PROJECT, DATE, ASSIGNEE, ATTACHMENT, PRIORITY, REMINDERS, LABELS, DEADLINE, LOCATION")
        @QuickAddChips
        List<String> quickAddChips,

        @Schema(description = "M6b: Quick Add hiện chữ cạnh biểu tượng chip (\"Show action labels\")")
        Boolean quickAddShowActionLabels,

        @Schema(description = "M6b: Quick Add hiện ô mô tả (\"Show description\")")
        Boolean quickAddShowDescription,
        @Schema(description = "M6c: Reset sub-tasks; vắng hoặc null = giữ nguyên")
        Boolean resetSubtasks,
        // ---- M10 (Settings → Productivity): vắng hoặc null = giữ nguyên
        @Schema(minimum = "1", maximum = "200", description = "Mục tiêu ngày (số công việc hoàn thành)")
        @Min(value = 1, message = "{validation.daily-goal.range}")
        @Max(value = 200, message = "{validation.daily-goal.range}")
        Integer dailyGoal,

        @Schema(minimum = "1", maximum = "1000", description = "Mục tiêu tuần (số công việc hoàn thành)")
        @Min(value = 1, message = "{validation.weekly-goal.range}")
        @Max(value = 1000, message = "{validation.weekly-goal.range}")
        Integer weeklyGoal,

        @Schema(description = "Ngày nghỉ: ngày ISO 1 = Thứ Hai … 7 = Chủ nhật, khác nhau, tối đa 6; [] = không nghỉ")
        @DaysOff
        List<Integer> daysOff,

        @Schema(description = "Vacation mode: giữ streak, không bị trừ Karma")
        Boolean vacationMode,

        @Schema(description = "Chúc mừng khi đạt mục tiêu ngày / tuần")
        Boolean goalCelebrations,

        @Schema(description = "Hiện Karma")
        Boolean karmaEnabled,
        // ---- M12 (contract mục 2)
        @Schema(nullable = true, description = "Số phút Hoãn: 5, 10, 15, 30, 45, 60, 120, 180; vắng / null = giữ nguyên")
        @SnoozeMinutes
        Integer snoozeMinutes,

        @Schema(description = "Ghim thông báo nhắc (không vuốt bỏ được); vắng / null = giữ nguyên")
        Boolean pinReminders,

        @Schema(nullable = true, maxLength = 64,
                description = "Id cài đặt của thiết bị nhận urgent reminder; vắng = giữ nguyên, null = bỏ chọn")
        Optional<@Pattern(regexp = "[A-Za-z0-9-]{8,64}", message = "{validation.urgent-device-id.invalid}")
                String> urgentDeviceId) {
}
