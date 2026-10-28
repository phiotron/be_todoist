package com.taskly.service;

import com.taskly.dto.productivity.ProjectInsightsResponse;
import com.taskly.repository.ProjectInsightsRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Insights của dự án (M10 plan F6). Dự án lưu trữ vẫn xem được; không đọc được → 404. */
@Service
@Transactional(readOnly = true)
public class ProjectInsightsService {

    static final int DAYS = 14;

    private final ProjectService projectService;
    private final UserService userService;
    private final ProjectInsightsRepository repository;
    private final Clock clock;

    public ProjectInsightsService(ProjectService projectService, UserService userService,
                                  ProjectInsightsRepository repository, Clock clock) {
        this.projectService = projectService;
        this.userService = userService;
        this.repository = repository;
        this.clock = clock;
    }

    public ProjectInsightsResponse get(UUID userId, UUID projectId) {
        projectService.requireReadable(userId, projectId);
        ZoneId zone = userService.timeZoneOf(userId);
        LocalDate today = TaskDueRules.today(clock.instant(), zone);
        ProjectInsightsRepository.Counts counts = repository.counts(projectId, today);
        LocalDate from = today.minusDays(DAYS - 1);
        Map<LocalDate, Integer> perDay = repository.completedByDay(projectId,
                from.atStartOfDay(zone).toInstant(), zone.getId());
        List<ProjectInsightsResponse.DayCount> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(today); day = day.plusDays(1)) {
            days.add(new ProjectInsightsResponse.DayCount(day, perDay.getOrDefault(day, 0)));
        }
        int percent = counts.total() == 0 ? 0 : counts.completed() * 100 / counts.total();
        return new ProjectInsightsResponse(projectId, counts.total(), counts.completed(),
                counts.total() - counts.completed(), counts.overdue(), percent, days);
    }
}
