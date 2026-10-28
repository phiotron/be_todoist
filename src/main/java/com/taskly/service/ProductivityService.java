package com.taskly.service;

import com.taskly.dto.productivity.ProductivityResponse;
import com.taskly.entity.KarmaLevel;
import com.taskly.entity.User;
import com.taskly.repository.KarmaEventRepository;
import com.taskly.repository.VacationPeriodRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Màn Productivity (GET /api/v1/productivity, M10 plan F3–F4). Ghi (không readOnly): áp phạt quá hạn còn thiếu trước khi
 * đọc. Ngày theo múi giờ tài khoản; tuần bắt đầu theo users.start_day.
 */
@Service
@Transactional
public class ProductivityService {

    static final int DAILY_DAYS = 7;
    static final int WEEKLY_WEEKS = 4;
    static final int TREND_DAYS = 28;

    private final UserService userService;
    private final KarmaService karmaService;
    private final KarmaEventRepository karmaEvents;
    private final VacationPeriodRepository vacationPeriods;
    private final Clock clock;

    public ProductivityService(UserService userService, KarmaService karmaService, KarmaEventRepository karmaEvents,
                               VacationPeriodRepository vacationPeriods, Clock clock) {
        this.userService = userService;
        this.karmaService = karmaService;
        this.karmaEvents = karmaEvents;
        this.vacationPeriods = vacationPeriods;
        this.clock = clock;
    }

    public ProductivityResponse get(UUID userId) {
        User user = userService.requireUser(userId);
        LocalDate today = TaskDueRules.today(clock.instant(), ZoneId.of(user.getTimeZone()));
        karmaService.applyOverduePenalties(userId, today);

        Map<LocalDate, Integer> counts = karmaEvents.completionsByDay(userId);
        List<VacationPeriodRepository.Period> periods = vacationPeriods.findByUser(userId);
        Set<Integer> daysOff = new HashSet<>(user.getDaysOff());
        Predicate<LocalDate> vacation = day -> periods.stream().anyMatch(p -> p.contains(day, today));
        Predicate<LocalDate> dayOff = day -> daysOff.contains(day.getDayOfWeek().getValue());

        return new ProductivityResponse(today, user.getDailyGoal(), user.getWeeklyGoal(), user.getDaysOff(),
                user.isVacationMode(), user.isKarmaEnabled(), user.isGoalCelebrations(),
                daily(user, today, counts, dayOff, vacation),
                weekly(user, today, counts, vacation),
                user.isKarmaEnabled() ? karma(userId, today) : null);
    }

    private ProductivityResponse.Daily daily(User user, LocalDate today, Map<LocalDate, Integer> counts,
                                             Predicate<LocalDate> dayOff, Predicate<LocalDate> vacation) {
        List<ProductivityResponse.Day> days = new ArrayList<>();
        for (LocalDate day = today.minusDays(DAILY_DAYS - 1); !day.isAfter(today); day = day.plusDays(1)) {
            days.add(new ProductivityResponse.Day(day, counts.getOrDefault(day, 0), dayOff.test(day),
                    vacation.test(day)));
        }
        int completed = counts.getOrDefault(today, 0);
        StreakCalculator.Streak streak = StreakCalculator.daily(counts, today, user.getDailyGoal(),
                dayOff.or(vacation));
        return new ProductivityResponse.Daily(completed, user.getDailyGoal(), completed >= user.getDailyGoal(),
                streak.current(), streak.longest(), days);
    }

    private ProductivityResponse.Weekly weekly(User user, LocalDate today, Map<LocalDate, Integer> counts,
                                               Predicate<LocalDate> vacation) {
        LocalDate thisWeek = StreakCalculator.weekStart(today, user.getStartDay());
        Map<LocalDate, Integer> byWeek = StreakCalculator.sumByWeek(counts, user.getStartDay());
        List<ProductivityResponse.Week> weeks = new ArrayList<>();
        for (int i = WEEKLY_WEEKS - 1; i >= 0; i--) {
            LocalDate week = thisWeek.minusWeeks(i);
            weeks.add(new ProductivityResponse.Week(week, byWeek.getOrDefault(week, 0)));
        }
        int completed = byWeek.getOrDefault(thisWeek, 0);
        Predicate<LocalDate> vacationWeek = week -> week.datesUntil(week.plusDays(7)).anyMatch(vacation);
        StreakCalculator.Streak streak = StreakCalculator.weekly(counts, thisWeek, user.getWeeklyGoal(), vacationWeek);
        return new ProductivityResponse.Weekly(completed, user.getWeeklyGoal(), completed >= user.getWeeklyGoal(),
                streak.current(), streak.longest(), thisWeek, weeks);
    }

    private ProductivityResponse.Karma karma(UUID userId, LocalDate today) {
        long karma = Math.max(0, karmaEvents.total(userId));
        LocalDate from = today.minusDays(TREND_DAYS - 1);
        Map<LocalDate, Long> perDay = karmaEvents.pointsByDay(userId, from, today);
        long running = karmaEvents.sumBefore(userId, from);
        List<ProductivityResponse.TrendPoint> trend = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            running += perDay.getOrDefault(day, 0L);
            trend.add(new ProductivityResponse.TrendPoint(day, Math.max(0, running)));
        }
        KarmaLevel level = KarmaLevel.of(karma);
        KarmaLevel next = level.next();
        List<ProductivityResponse.LevelInfo> levels = Arrays.stream(KarmaLevel.values())
                .map(l -> new ProductivityResponse.LevelInfo(l, l.minKarma())).toList();
        return new ProductivityResponse.Karma(karma, level, next, next == null ? null : next.minKarma(),
                next == null ? 0 : next.minKarma() - karma, trend, levels);
    }
}
