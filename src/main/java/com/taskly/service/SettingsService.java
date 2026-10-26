package com.taskly.service;

import com.taskly.dto.settings.SettingsResponse;
import com.taskly.dto.settings.UpdateSettingsRequest;
import com.taskly.entity.Theme;
import com.taskly.entity.User;
import com.taskly.mapper.SettingsMapper;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cài đặt theo tài khoản (giao diện, ngôn ngữ, múi giờ; M4 thêm nhắc việc). Dữ liệu nằm ở bảng users nên lấy entity
 * qua UserService (không gọi UserRepository). Gán giá trị giống hệt → không UPDATE → updated_at giữ nguyên.
 */
@Service
@Transactional
public class SettingsService {

    private final UserService userService;

    public SettingsService(UserService userService) {
        this.userService = userService;
    }

    @Transactional(readOnly = true)
    public SettingsResponse get(UUID userId) {
        return SettingsMapper.toResponse(userService.requireUser(userId));
    }

    /** PATCH từng phần: field null = giữ nguyên (DTO đã kiểm giá trị). */
    public SettingsResponse update(UUID userId, UpdateSettingsRequest request) {
        User user = userService.requireUser(userId);
        if (request.theme() != null) {
            user.changeTheme(Theme.valueOf(request.theme()));
        }
        if (request.locale() != null) {
            user.changeLocale(request.locale());
        }
        if (request.timeZone() != null) {
            user.changeTimeZone(request.timeZone());
        }
        return SettingsMapper.toResponse(user);
    }

    /** Về mặc định: SYSTEM, vi; múi giờ giữ nguyên (User.resetSettings). App hỏi xác nhận trước khi gọi (BR-11). */
    public SettingsResponse reset(UUID userId) {
        User user = userService.requireUser(userId);
        user.resetSettings();
        return SettingsMapper.toResponse(user);
    }
}
