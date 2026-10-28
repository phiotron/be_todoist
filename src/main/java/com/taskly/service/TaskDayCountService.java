package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.dto.task.DayCountsResponse;
import com.taskly.repository.TaskDayCountRepository;
import com.taskly.security.AccessPolicy;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Số task đang mở mỗi ngày cho lịch của Date sheet (M6b, contract 8.2). Phạm vi quyền = AccessPolicy (BR-09). */
@Service
@Transactional(readOnly = true)
public class TaskDayCountService {

    /** Tối đa 62 ngày mỗi lần (to − from ≤ 61): đủ hai tháng lịch. */
    static final int MAX_DAYS = 62;

    private final AccessPolicy accessPolicy;
    private final TaskDayCountRepository repository;

    public TaskDayCountService(AccessPolicy accessPolicy, TaskDayCountRepository repository) {
        this.accessPolicy = accessPolicy;
        this.repository = repository;
    }

    public DayCountsResponse count(UUID userId, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw ApiException.badRequest(ErrorMessage.DAY_COUNTS_RANGE_INVALID);
        }
        Map<LocalDate, Integer> found = repository.countOpenByDueDay(
                accessPolicy.listScope(userId), from, to);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            counts.put(day.toString(), found.getOrDefault(day, 0));
        }
        return new DayCountsResponse(counts);
    }
}
