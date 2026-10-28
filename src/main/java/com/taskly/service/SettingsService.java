package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.FieldErrorItem;
import com.taskly.common.validation.SettingsTargets;
import com.taskly.dto.settings.SettingsResponse;
import com.taskly.dto.settings.UpdateSettingsRequest;
import com.taskly.entity.Appearance;
import com.taskly.entity.DateFormat;
import com.taskly.entity.SwipeAction;
import com.taskly.entity.Theme;
import com.taskly.entity.TimeFormat;
import com.taskly.entity.User;
import com.taskly.mapper.SettingsMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cài đặt theo tài khoản (spec bản 3 mục 10, 12.1.1; M4 thêm nhắc việc). Dữ liệu nằm ở bảng users nên lấy entity qua
 * UserService (không gọi UserRepository). Gán giá trị giống hệt → không UPDATE → updated_at giữ nguyên.
 */
@Service
@Transactional
public class SettingsService {

    /** Key câu lỗi của errors[].message khi mục ghim không đọc được (messages_vi/en). */
    static final String TARGET_UNAVAILABLE_KEY = "validation.settings.target-unavailable";

    private final UserService userService;
    private final PinnedTargetChecker pinnedTargetChecker;
    private final ProductivitySettingsService productivitySettings; // M10

    public SettingsService(UserService userService, PinnedTargetChecker pinnedTargetChecker,
                           ProductivitySettingsService productivitySettings) {
        this.userService = userService;
        this.pinnedTargetChecker = pinnedTargetChecker;
        this.productivitySettings = productivitySettings;
    }

    @Transactional(readOnly = true)
    public SettingsResponse get(UUID userId) {
        return SettingsMapper.toResponse(userService.requireUser(userId));
    }

    /** PATCH từng phần: field null = giữ nguyên (DTO đã kiểm giá trị / định dạng). */
    public SettingsResponse update(UUID userId, UpdateSettingsRequest request) {
        User user = userService.requireUser(userId);
        requirePinnedTargetsReadable(userId, request);
        if (request.theme() != null) {
            user.changeTheme(Theme.valueOf(request.theme()));
        }
        if (request.appearance() != null) {
            user.changeAppearance(Appearance.valueOf(request.appearance()));
        }
        if (request.locale() != null) {
            user.changeLocale(request.locale());
        }
        if (request.timeZone() != null) {
            user.changeTimeZone(request.timeZone());
        }
        if (request.startDay() != null) {
            user.changeStartDay(request.startDay());
        }
        if (request.nextWeek() != null) {
            user.changeNextWeek(request.nextWeek());
        }
        if (request.weekendStartDay() != null) {
            user.changeWeekendStartDay(request.weekendStartDay());
        }
        if (request.dateFormat() != null) {
            user.changeDateFormat(DateFormat.valueOf(request.dateFormat()));
        }
        if (request.timeFormat() != null) {
            user.changeTimeFormat(TimeFormat.valueOf(request.timeFormat()));
        }
        if (request.startPage() != null) {
            user.changeStartPage(request.startPage());
        }
        if (request.smartDateRecognition() != null) {
            user.changeSmartDateRecognition(request.smartDateRecognition());
        }
        if (request.swipeLeft() != null) {
            user.changeSwipeLeft(SwipeAction.valueOf(request.swipeLeft()));
        }
        if (request.swipeRight() != null) {
            user.changeSwipeRight(SwipeAction.valueOf(request.swipeRight()));
        }
        if (request.navTabs() != null) {
            user.changeNavTabs(request.navTabs());
        }
        // M4: cài đặt nhắc việc. remindersEnabled null = giữ; autoReminderMinutes vắng = giữ, null = tắt.
        if (request.remindersEnabled() != null) {
            user.changeRemindersEnabled(request.remindersEnabled());
        }
        if (request.autoReminderMinutes() != null) {
            user.changeAutoReminderMinutes(request.autoReminderMinutes().orElse(null));
        }
        productivitySettings.apply(user, request); // M10: Settings → Productivity
        return SettingsMapper.toResponse(user);
    }

    /** Về mặc định spec mục 10; múi giờ giữ nguyên (User.resetSettings). App hỏi xác nhận trước khi gọi (BR-11). */
    public SettingsResponse reset(UUID userId) {
        User user = userService.requireUser(userId);
        user.resetSettings();
        return SettingsMapper.toResponse(user);
    }

    /** Kiểm xong mới sửa → request lỗi không đổi dòng nào (kể cả field hợp lệ gửi cùng). */
    private void requirePinnedTargetsReadable(UUID userId, UpdateSettingsRequest request) {
        List<FieldErrorItem> errors = new ArrayList<>();
        if (request.startPage() != null && SettingsTargets.pinned(request.startPage())
                .filter(target -> !pinnedTargetChecker.isReadable(userId, target)).isPresent()) {
            errors.add(new FieldErrorItem("startPage", TARGET_UNAVAILABLE_KEY));
        }
        if (request.navTabs() != null
                && (request.navTabs().stream().anyMatch(tab -> !pinnedTargetChecker.isTabAvailable(tab))
                || SettingsTargets.pinnedIn(request.navTabs()).stream()
                .anyMatch(target -> !pinnedTargetChecker.isReadable(userId, target)))) {
            errors.add(new FieldErrorItem("navTabs", TARGET_UNAVAILABLE_KEY));
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest(ErrorMessage.VALIDATION_FAILED).withProperty("errors", errors);
        }
    }
}
