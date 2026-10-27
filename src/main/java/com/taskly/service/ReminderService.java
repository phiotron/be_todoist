package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.dto.reminder.CreateReminderRequest;
import com.taskly.dto.reminder.ReminderResponse;
import com.taskly.entity.LimitKind;
import com.taskly.entity.Reminder;
import com.taskly.entity.ReminderType;
import com.taskly.entity.Task;
import com.taskly.mapper.ReminderMapper;
import com.taskly.repository.ReminderRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nhắc riêng của task (spec 5.8, 12.8). Quyền đi qua TaskService (→ ProjectService → AccessPolicy): không thấy → 404,
 * dự án / section lưu trữ → 409. Giới hạn 700 qua UsageLimits, có khoá hàng users chống đua (spec 8.2 #9). Nhắc thuộc
 * người tạo (reminders.user_id). Nghe TaskTimeClearedEvent của M1 để xoá nhắc tương đối khi task mất giờ.
 */
@Service
@Transactional
public class ReminderService {

    private final ReminderRepository reminderRepository;
    private final TaskService taskService;
    private final UserService userService;
    private final UsageLimits usageLimits;
    private final Clock clock;

    public ReminderService(ReminderRepository reminderRepository, TaskService taskService, UserService userService,
                           UsageLimits usageLimits, Clock clock) {
        this.reminderRepository = reminderRepository;
        this.taskService = taskService;
        this.userService = userService;
        this.usageLimits = usageLimits;
        this.clock = clock;
    }

    /** Đọc được cả khi task đã xong / dự án lưu trữ. Theo thứ tự tạo. */
    @Transactional(readOnly = true)
    public List<ReminderResponse> list(UUID userId, UUID taskId) {
        taskService.requireReadable(userId, taskId);
        return reminderRepository.findByTaskIdAndUserIdOrderByCreatedAtAscIdAsc(taskId, userId).stream()
                .map(ReminderMapper::toResponse)
                .toList();
    }

    /**
     * Thứ tự kiểm: 404 / 409 lưu trữ (requireWritable) → 409 task-completed → 400 (field, at đã qua, cần giờ) →
     * khoá users → 422 (vượt giới hạn). Khoá đặt SAU các kiểm tra không cần khoá để request lỗi không giữ khoá.
     */
    public ReminderResponse create(UUID userId, UUID taskId, CreateReminderRequest request) {
        Task task = taskService.requireWritable(userId, taskId);
        if (task.getCompletedAt() != null) {
            throw ApiException.conflict(ErrorMessage.REMINDER_TASK_COMPLETED).withCode(ProblemCodes.TASK_COMPLETED);
        }
        Reminder reminder = switch (request.type()) {
            case ABSOLUTE -> absolute(userId, task, request);
            case RELATIVE -> relative(userId, task, request);
        };
        // Chống đua (spec 8.2 #9): SELECT … FOR UPDATE hàng users của người tạo TRƯỚC khi đếm + insert. Hai POST song
        // song ở max − 1 thì request sau chờ request trước commit rồi đếm lại → đúng một thành công.
        usageLimits.lockUser(userId);
        usageLimits.check(LimitKind.REMINDERS, reminderRepository.countOnOpenTasks(userId), 1);
        return ReminderMapper.toResponse(reminderRepository.save(reminder));
    }

    /**
     * Dự án / section lưu trữ → 409 (chỉ đọc, spec 5.7). Task đã xong (trong dự án đang hoạt động) vẫn xoá được nhắc
     * → 204: chỉ POST bị 409 task-completed.
     */
    public void delete(UUID userId, UUID taskId, UUID reminderId) {
        taskService.requireWritable(userId, taskId);
        Reminder reminder = reminderRepository.findByIdAndTaskIdAndUserId(reminderId, taskId, userId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.REMINDER_NOT_FOUND));
        reminderRepository.delete(reminder);
    }

    /**
     * Task mất giờ (đổi sang chỉ ngày / bỏ due): M1 phát TaskTimeClearedEvent ĐỒNG BỘ trong transaction của
     * TaskService.applyDue (PATCH, tasks/batch SCHEDULE, Reschedule quá hạn) → xoá nhắc tương đối của các task đó
     * trong CÙNG transaction (lỗi ghi due → rollback cả xoá nhắc). Nhắc tuyệt đối giữ nguyên (spec 5.8, 10.1).
     */
    @EventListener
    public void onTaskTimeCleared(TaskTimeClearedEvent event) {
        if (event.taskIds().isEmpty()) {
            return;
        }
        reminderRepository.deleteByTaskIdsAndType(event.taskIds(), ReminderType.RELATIVE);
    }

    private Reminder absolute(UUID userId, Task task, CreateReminderRequest request) {
        if (request.at() == null || request.minuteOffset() != null) {
            throw ApiException.badRequest(ErrorMessage.REMINDER_FIELDS_MISMATCH);
        }
        LocalDateTime at = ReminderWindow.toMinute(request.at());
        ReminderWindow window = ReminderWindow.at(clock.instant(), userService.timeZoneOf(userId));
        if (window.hasPassed(at)) {
            throw ApiException.badRequest(ErrorMessage.REMINDER_AT_PAST);
        }
        return Reminder.absolute(task.getId(), userId, at);
    }

    private Reminder relative(UUID userId, Task task, CreateReminderRequest request) {
        if (request.minuteOffset() == null || request.at() != null) {
            throw ApiException.badRequest(ErrorMessage.REMINDER_FIELDS_MISMATCH);
        }
        if (task.getDueDatetime() == null) {
            throw ApiException.badRequest(ErrorMessage.REMINDER_NEEDS_DUE_TIME);
        }
        return Reminder.relative(task.getId(), userId, request.minuteOffset());
    }
}
