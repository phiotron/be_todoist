package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.web.CursorCodec;
import com.taskly.dto.common.Page;
import com.taskly.dto.notification.NotificationResponse;
import com.taskly.dto.notification.NotificationSettingItem;
import com.taskly.dto.notification.NotificationSettingsResponse;
import com.taskly.dto.notification.UnreadCountResponse;
import com.taskly.dto.notification.UpdateNotificationSettingsRequest;
import com.taskly.entity.NotificationEvent;
import com.taskly.entity.NotificationType;
import com.taskly.mapper.CommentMapper;
import com.taskly.repository.NotificationRepository;
import com.taskly.repository.NotificationRepository.NotificationRow;
import com.taskly.security.AccessPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hộp thông báo + cài đặt Project / Workspace notifications (M8b). Phạm vi đọc = AccessPolicy.readableWorkspaceIds
 * (mất quyền xem dự án → thông báo của nó ẩn). Nguồn thông báo lượt này: @mention trong comment (CommentService).
 */
@Service
@Transactional
public class NotificationService {

    /** Độ dài trích comment trong thông báo. */
    static final int EXCERPT_LENGTH = 200;

    private final NotificationRepository repository;
    private final AccessPolicy accessPolicy;
    private final Clock clock;

    public NotificationService(NotificationRepository repository, AccessPolicy accessPolicy, Clock clock) {
        this.repository = repository;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    /** Gọi trong transaction tạo comment. Người nhận tắt cờ PROJECT / COMMENT_MENTION → không ghi. */
    public void notifyMention(UUID recipientId, UUID actorId, UUID commentId, UUID taskId, UUID projectId) {
        if (recipientId.equals(actorId) || !repository.isEnabled(recipientId, NotificationEvent.COMMENT_MENTION)) {
            return;
        }
        repository.insert(UUID.randomUUID(), recipientId, NotificationType.COMMENT_MENTION, actorId, commentId,
                taskId, projectId, clock.instant());
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> list(UUID userId, boolean unreadOnly, String cursor, Integer limit) {
        int pageSize = CursorCodec.limit(limit);
        String kind = unreadOnly ? "notifications:unread" : "notifications:all";
        Instant afterCreatedAt = null;
        UUID afterId = null;
        if (CursorCodec.hasCursor(cursor)) {
            CursorCodec.Cursor after = CursorCodec.decode(kind, cursor, 2);
            afterCreatedAt = after.instant(0);
            afterId = after.uuid(1);
        }
        List<UUID> workspaceIds = accessPolicy.readableWorkspaceIds(userId);
        if (workspaceIds.isEmpty()) {
            return new Page<>(List.of(), null);
        }
        List<NotificationRow> rows = repository.page(userId, workspaceIds, unreadOnly, afterCreatedAt, afterId,
                pageSize + 1);
        return Page.fromLookahead(rows, pageSize,
                        row -> CursorCodec.encode(kind, List.of(row.createdAt().toString(), row.id().toString())))
                .map(NotificationService::toResponse);
    }

    @Transactional(readOnly = true)
    public UnreadCountResponse unreadCount(UUID userId) {
        List<UUID> workspaceIds = accessPolicy.readableWorkspaceIds(userId);
        return new UnreadCountResponse(workspaceIds.isEmpty() ? 0 : repository.countUnread(userId, workspaceIds));
    }

    /** Của người khác / không còn xem được → 404. Đã đọc → giữ nguyên (204). */
    public void markRead(UUID userId, UUID notificationId) {
        List<UUID> workspaceIds = accessPolicy.readableWorkspaceIds(userId);
        if (workspaceIds.isEmpty() || !repository.isVisible(notificationId, userId, workspaceIds)) {
            throw ApiException.notFound(ErrorMessage.NOTIFICATION_NOT_FOUND);
        }
        repository.markRead(notificationId, userId, clock.instant());
    }

    public void markAllRead(UUID userId) {
        repository.markAllRead(userId, clock.instant());
    }

    // ---- cài đặt

    @Transactional(readOnly = true)
    public NotificationSettingsResponse settings(UUID userId) {
        Map<NotificationEvent, Boolean> saved = repository.settings(userId);
        return new NotificationSettingsResponse(Arrays.stream(NotificationEvent.values())
                .map(event -> new NotificationSettingItem(event.scope(), event, saved.getOrDefault(event, true)))
                .toList());
    }

    /** scope không khớp event → 400 (kiểm hết trước khi ghi). */
    public NotificationSettingsResponse updateSettings(UUID userId, UpdateNotificationSettingsRequest request) {
        for (NotificationSettingItem item : request.items()) {
            if (item.event().scope() != item.scope()) {
                throw ApiException.badRequest(ErrorMessage.NOTIFICATION_SETTING_INVALID);
            }
        }
        for (NotificationSettingItem item : request.items()) {
            repository.upsertSetting(userId, item.event(), item.enabled());
        }
        return settings(userId);
    }

    private static NotificationResponse toResponse(NotificationRow row) {
        return new NotificationResponse(row.id(), row.type(),
                CommentMapper.author(row.actorId(), row.actorName(), row.actorAvatarAt()), row.taskId(),
                row.taskContent(), row.projectId(), row.projectName(), row.commentId(), excerpt(row.commentContent()),
                row.readAt() != null, row.createdAt());
    }

    static String excerpt(String content) {
        if (content == null) {
            return null;
        }
        String flat = content.strip().replaceAll("\\s+", " ");
        return flat.length() <= EXCERPT_LENGTH ? flat : flat.substring(0, EXCERPT_LENGTH - 1) + "…";
    }
}
