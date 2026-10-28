package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.web.CursorCodec;
import com.taskly.dto.activity.ActivityResponse;
import com.taskly.dto.common.Page;
import com.taskly.entity.ActivityEventType;
import com.taskly.entity.ActivityObjectType;
import com.taskly.repository.ActivityLogRepository;
import com.taskly.security.AccessPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ĐỌC Activity log (GET /api/v1/activity, M10 plan F5): dòng của các workspace user đọc được, mới nhất trước, phân trang
 * con trỏ (created_at, id). Lọc theo dự án / task (phải đọc được, không → 404), loại đối tượng, loại sự kiện (tham số
 * query viết thường như spec 12), người làm (initiatorId — dữ liệu cộng tác có từ M8).
 */
@Service
@Transactional(readOnly = true)
public class ActivityQueryService {

    static final String CURSOR_KIND = "activity";

    private final ActivityLogRepository repository;
    private final AccessPolicy accessPolicy;
    private final ProjectService projectService;
    private final TaskService taskService;

    public ActivityQueryService(ActivityLogRepository repository, AccessPolicy accessPolicy,
                                ProjectService projectService, TaskService taskService) {
        this.repository = repository;
        this.accessPolicy = accessPolicy;
        this.projectService = projectService;
        this.taskService = taskService;
    }

    /** Tham số thô từ query string (null = không lọc). */
    public record Filter(UUID projectId, UUID taskId, String objectType, String eventType, UUID initiatorId,
                         String cursor, Integer limit) {
    }

    public Page<ActivityResponse> list(UUID userId, Filter filter) {
        int limit = CursorCodec.limit(filter.limit());
        ActivityObjectType objectType = parse(ActivityObjectType.class, filter.objectType());
        ActivityEventType eventType = parse(ActivityEventType.class, filter.eventType());
        Instant afterCreatedAt = null;
        UUID afterId = null;
        if (CursorCodec.hasCursor(filter.cursor())) {
            CursorCodec.Cursor cursor = CursorCodec.decode(CURSOR_KIND, filter.cursor(), 2);
            afterCreatedAt = cursor.instant(0);
            afterId = cursor.uuid(1);
        }
        if (filter.projectId() != null) {
            projectService.requireReadable(userId, filter.projectId());
        }
        if (filter.taskId() != null) {
            taskService.requireReadable(userId, filter.taskId());
        }
        List<UUID> workspaceIds = accessPolicy.readableWorkspaceIds(userId);
        if (workspaceIds.isEmpty()) {
            return new Page<>(List.of(), null);
        }
        List<ActivityResponse> rows = repository.page(new ActivityLogRepository.Query(workspaceIds,
                filter.projectId(), filter.taskId(), objectType, eventType, filter.initiatorId(), afterCreatedAt,
                afterId, limit));
        return Page.fromLookahead(rows, limit, last ->
                CursorCodec.encode(CURSOR_KIND, List.of(last.createdAt().toString(), last.id().toString())));
    }

    /** Giá trị query viết thường ("completed") → enum; viết hoa / lạ → 400. */
    private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        if (!raw.equals(raw.toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest(ErrorMessage.ACTIVITY_QUERY_INVALID);
        }
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(ErrorMessage.ACTIVITY_QUERY_INVALID);
        }
    }
}
