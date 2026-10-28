package com.taskly.dto.settings;

import com.taskly.entity.AddButtonPlacement;
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
        // M3x (điều hướng):
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Vị trí nút + nổi")
        AddButtonPlacement addButtonPlacement,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Sync home view: false = mỗi máy giữ trang mở đầu riêng (app lưu trên máy)")
        boolean syncHomeView,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Navigation menu: hiện số task cạnh mục menu")
        boolean navMenuShowTaskCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Navigation menu: mục đang hiện theo thứ tự (vắng = ẩn): INBOX, TODAY, UPCOMING, "
                        + "FILTERS_LABELS, REPORTING, BROWSE_TEMPLATES")
        List<String> navMenuItems,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "M6b: chip Quick Add theo thứ tự hiển thị (vắng = ẩn, PROJECT luôn có): PROJECT, DATE, "
                        + "ASSIGNEE, ATTACHMENT, PRIORITY, REMINDERS, LABELS, DEADLINE, LOCATION")
        List<String> quickAddChips,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "M6b: \"Show action labels\"")
        boolean quickAddShowActionLabels,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "M6b: \"Show description\"")
        boolean quickAddShowDescription,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "M6c: tự mở lại task con (≤ 30 con trực tiếp) khi hoàn thành task lặp")
        boolean resetSubtasks,
        // ---- M10 (Settings → Productivity)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "200") int dailyGoal,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "1000") int weeklyGoal,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Ngày nghỉ ISO (1 = Thứ Hai … 7 = Chủ nhật), tăng dần; không tính vào streak")
        List<Integer> daysOff,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean vacationMode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean goalCelebrations,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean karmaEnabled,
        // ---- M12 (contract mục 2)
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "5", maximum = "180",
                description = "Số phút của nút Hoãn trên thông báo nhắc: 5, 10, 15, 30, 45, 60, 120, 180 (mặc định 15)")
        int snoozeMinutes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "Ghim thông báo nhắc: không vuốt bỏ được tới khi người dùng bấm")
        boolean pinReminders,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                description = "Id cài đặt của thiết bị nhận urgent reminder; null = chưa chọn")
        String urgentDeviceId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "M8a: lời mời email tới tài khoản này được chấp nhận ngay (Auto accept invites)")
        boolean autoAcceptInvites) {
}
