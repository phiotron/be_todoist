package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.entity.ActivityEventType;
import com.taskly.entity.NotificationType;
import com.taskly.entity.Task;
import com.taskly.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chờ duyệt (review) — phần GHI trạng thái + nhật ký + thông báo. Quyền do AccessPolicy quyết (mustRequestReview,
 * checkReviewDecision); việc hoàn thành thật khi duyệt do TaskCompletionService làm (xử lý việc lặp, cây con, karma).
 * Gọi trong transaction của thao tác (cùng commit / rollback).
 */
@Service
@Transactional
public class TaskReviewService {

    private final ActivityService activityService;
    private final NotificationRepository notifications;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public TaskReviewService(ActivityService activityService, NotificationRepository notifications,
                             NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.activityService = activityService;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Member / Guest tick: chưa PENDING → gửi yêu cầu (thông báo mọi Admin của workspace); PENDING do chính mình → huỷ;
     * PENDING do người khác → không đổi. Trả id task ĐÃ ĐỔI.
     */
    public List<UUID> requestOrCancel(UUID userId, Task task) {
        if (!task.isReviewPending()) {
            Instant now = clock.instant();
            task.requestReview(userId, now);
            activityService.task(userId, task, ActivityEventType.REVIEW_REQUESTED);
            for (UUID adminId : adminIds(task.getWorkspaceId())) {
                if (!adminId.equals(userId)) {
                    notify(adminId, NotificationType.REVIEW_REQUESTED, userId, task, now);
                }
            }
            return List.of(task.getId());
        }
        if (userId.equals(task.getReviewRequestedBy())) {
            cancel(userId, task);
            return List.of(task.getId());
        }
        return List.of();
    }

    /** Người yêu cầu (hoặc Admin) huỷ yêu cầu đang PENDING. */
    public void cancel(UUID actorId, Task task) {
        task.clearReview();
        activityService.task(actorId, task, ActivityEventType.REVIEW_CANCELLED);
    }

    /** Sau khi Admin duyệt (task đã hoàn thành thật): nhật ký + thông báo người yêu cầu. */
    public void approved(UUID adminId, UUID requesterId, Task task) {
        activityService.task(adminId, task, ActivityEventType.REVIEW_APPROVED);
        if (requesterId != null && !requesterId.equals(adminId)) {
            notify(requesterId, NotificationType.REVIEW_APPROVED, adminId, task, clock.instant());
        }
    }

    /** Admin trả lại: về NONE, vẫn mở; nhật ký + thông báo người yêu cầu. */
    public void rejected(UUID adminId, Task task) {
        UUID requesterId = task.getReviewRequestedBy();
        task.clearReview();
        activityService.task(adminId, task, ActivityEventType.REVIEW_REJECTED);
        if (requesterId != null && !requesterId.equals(adminId)) {
            notify(requesterId, NotificationType.REVIEW_REJECTED, adminId, task, clock.instant());
        }
    }

    /**
     * Admin hoàn tác "Trả lại": về PENDING với người yêu cầu + thời điểm cũ; nhật ký REVIEW_RESTORED; xoá thông báo
     * REVIEW_REJECTED CHƯA ĐỌC mới nhất của người yêu cầu (đã đọc thì giữ). Không gửi thông báo mới.
     * requestedBy không thuộc workspace của việc → 400 validation-failed.
     */
    public void restored(UUID adminId, Task task, UUID requestedBy, Instant requestedAt) {
        Integer member = jdbc.queryForObject("select count(*) from workspace_members where workspace_id = :w "
                + "and user_id = :u", new MapSqlParameterSource("w", task.getWorkspaceId()).addValue("u", requestedBy),
                Integer.class);
        if (member == null || member == 0) {
            throw ApiException.badRequest(ErrorMessage.VALIDATION_FAILED).withProperty("field", "requestedBy");
        }
        task.restoreReview(requestedBy, requestedAt);
        activityService.task(adminId, task, ActivityEventType.REVIEW_RESTORED);
        notifications.deleteLatestUnread(requestedBy, NotificationType.REVIEW_REJECTED, task.getId());
    }

    private void notify(UUID recipientId, NotificationType type, UUID actorId, Task task, Instant now) {
        notifications.insert(UUID.randomUUID(), recipientId, type, actorId, null, task.getId(), task.getProjectId(),
                now);
    }

    private List<UUID> adminIds(UUID workspaceId) {
        return jdbc.queryForList("select user_id from workspace_members where workspace_id = :workspaceId "
                + "and role = 'ADMIN' order by user_id", new MapSqlParameterSource("workspaceId", workspaceId),
                UUID.class);
    }
}
