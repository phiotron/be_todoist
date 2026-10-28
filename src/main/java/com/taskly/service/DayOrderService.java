package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.entity.Task;
import com.taskly.repository.TaskListingRepository;
import com.taskly.security.AccessPolicy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DND — reorder type TASK_DAY: thứ tự trong NGÀY (day order kiểu Todoist) cho Today / Upcoming khi sort = date.
 * Vùng chứa = task mở, cùng due_day, dự án chưa lưu trữ trong phạm vi đọc của user (khác dự án vẫn chung một ngày).
 * Đánh số lại CẢ ngày (bước 1024) theo thứ tự đang hiện rồi đặt id ngay sau afterId (null = đầu ngày).
 * Native UPDATE: không tăng version (như reorder). day_order dùng chung cho mọi thành viên của dự án chia sẻ.
 */
@Service
@Transactional
public class DayOrderService {

    static final long STEP = 1024;

    private final TaskService taskService;
    private final TaskListingRepository listing;
    private final AccessPolicy accessPolicy;
    private final UsageLimits usageLimits;

    public DayOrderService(TaskService taskService, TaskListingRepository listing, AccessPolicy accessPolicy,
                           UsageLimits usageLimits) {
        this.taskService = taskService;
        this.listing = listing;
        this.accessPolicy = accessPolicy;
        this.usageLimits = usageLimits;
    }

    public long reorder(UUID userId, UUID taskId, UUID afterId) {
        Task task = taskService.lockAndRecheck(taskService.requireWritable(userId, taskId));
        if (task.getCompletedAt() != null) {
            throw ApiException.conflict(ErrorMessage.TASK_COMPLETED).withCode(ProblemCodes.TASK_COMPLETED);
        }
        LocalDate day = TaskDueRules.dueDay(task.getDueDate(), task.getDueDatetime());
        if (day == null) {
            throw ApiException.badRequest(ErrorMessage.DAY_ORDER_REQUIRES_DUE);
        }
        List<UUID> others = new ArrayList<>(listing.dayOrderIds(accessPolicy.listScope(userId), day));
        others.remove(taskId);
        int at = 0;
        if (afterId != null) {
            int index = others.indexOf(afterId);
            if (index < 0) {
                throw ApiException.badRequest(ErrorMessage.AFTER_ID_NOT_IN_CONTAINER);
            }
            at = index + 1;
        }
        others.add(at, taskId);
        usageLimits.lockTasks(others); // hàng task, id tăng dần (quy tắc khoá) — sau khoá P của lockAndRecheck
        Map<UUID, Long> orders = new LinkedHashMap<>();
        for (int i = 0; i < others.size(); i++) {
            orders.put(others.get(i), (i + 1) * STEP);
        }
        listing.writeDayOrders(orders, day);
        return orders.get(taskId);
    }
}
