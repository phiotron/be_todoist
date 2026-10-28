package com.taskly.service;

import com.taskly.dto.settings.UpdateSettingsRequest;
import com.taskly.entity.User;
import com.taskly.repository.VacationPeriodRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phần M10 của PATCH me/settings (Settings → Productivity). SettingsService gọi apply(...) sau khi đã áp các field M3/M4
 * — tách riêng để file của M3 chỉ thêm một dòng. Field null = giữ nguyên (DTO đã kiểm giá trị).
 * Vacation mode: tắt → bật mở một kỳ nghỉ từ hôm nay (múi giờ tài khoản); bật → tắt đóng kỳ tại hôm nay; gửi lại cùng
 * giá trị không đổi gì.
 */
@Service
@Transactional
public class ProductivitySettingsService {

    private final VacationPeriodRepository vacationPeriods;
    private final Clock clock;

    public ProductivitySettingsService(VacationPeriodRepository vacationPeriods, Clock clock) {
        this.vacationPeriods = vacationPeriods;
        this.clock = clock;
    }

    public void apply(User user, UpdateSettingsRequest request) {
        if (request.dailyGoal() != null) {
            user.changeDailyGoal(request.dailyGoal());
        }
        if (request.weeklyGoal() != null) {
            user.changeWeeklyGoal(request.weeklyGoal());
        }
        if (request.daysOff() != null) {
            user.changeDaysOff(request.daysOff());
        }
        if (request.goalCelebrations() != null) {
            user.changeGoalCelebrations(request.goalCelebrations());
        }
        if (request.karmaEnabled() != null) {
            user.changeKarmaEnabled(request.karmaEnabled());
        }
        if (request.vacationMode() != null && request.vacationMode() != user.isVacationMode()) {
            LocalDate today = TaskDueRules.today(clock.instant(), ZoneId.of(user.getTimeZone()));
            if (request.vacationMode()) {
                vacationPeriods.open(user.getId(), today, clock.instant());
            } else {
                vacationPeriods.close(user.getId(), today);
            }
            user.changeVacationMode(request.vacationMode());
        }
    }
}
