package com.taskly.service;

import com.taskly.entity.User;
import com.taskly.repository.KarmaEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Karma (M10 plan F2). Công thức Todoist không công bố → QUYẾT ĐỊNH TẠM của Taskly (chờ người dùng duyệt):
 * hoàn thành một task +5; mở lại / Undo trừ lại đúng lần cộng gần nhất; đạt mục tiêu ngày lần đầu trong ngày +10; đạt
 * mục tiêu tuần lần đầu trong tuần +30; task đang mở do mình tạo quá hạn ≥ 5 ngày −2 (một lần cho mỗi task + hạn, không
 * phạt trong kỳ Vacation). Điểm ghi cả khi người dùng tắt Karma (karmaEnabled chỉ ẩn / hiện).
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class KarmaService {

    public static final int TASK_POINTS = 5;
    public static final int DAILY_GOAL_POINTS = 10;
    public static final int WEEKLY_GOAL_POINTS = 30;
    public static final int OVERDUE_POINTS = -2;
    public static final int OVERDUE_DAYS = 5;

    private final KarmaEventRepository repository;
    private final UserService userService;
    private final Clock clock;

    public KarmaService(KarmaEventRepository repository, UserService userService, Clock clock) {
        this.repository = repository;
        this.userService = userService;
        this.clock = clock;
    }

    /** Cộng cho người hoàn thành (ngày theo múi giờ của họ) rồi xét thưởng mục tiêu ngày / tuần. Rỗng → bỏ qua. */
    public void tasksCompleted(UUID userId, Collection<UUID> taskIds) {
        if (taskIds.isEmpty()) {
            return;
        }
        User user = userService.requireUser(userId);
        Instant now = clock.instant();
        LocalDate today = TaskDueRules.today(now, ZoneId.of(user.getTimeZone()));
        repository.insertCompletions(userId, List.copyOf(taskIds), TASK_POINTS, today, now);
        if (repository.completedOn(userId, today) >= user.getDailyGoal()) {
            repository.insertGoalBonus(userId, KarmaEventRepository.DAILY_GOAL, DAILY_GOAL_POINTS, today, today, now);
        }
        LocalDate weekStart = StreakCalculator.weekStart(today, user.getStartDay());
        if (repository.completedBetween(userId, weekStart, weekStart.plusDays(6)) >= user.getWeeklyGoal()) {
            repository.insertGoalBonus(userId, KarmaEventRepository.WEEKLY_GOAL, WEEKLY_GOAL_POINTS, today, weekStart,
                    now);
        }
    }

    /** Huỷ lần cộng gần nhất của từng task (thưởng mục tiêu đã nhận giữ nguyên — tạm). */
    public void tasksReopened(Collection<UUID> taskIds) {
        repository.reverseCompletions(List.copyOf(taskIds), clock.instant());
    }

    /** Áp các lần phạt quá hạn còn thiếu (gọi khi đọc Productivity — chưa có job nền, tạm). */
    public void applyOverduePenalties(UUID userId, LocalDate today) {
        repository.insertOverduePenalties(userId, OVERDUE_POINTS, OVERDUE_DAYS, today, clock.instant());
    }
}
