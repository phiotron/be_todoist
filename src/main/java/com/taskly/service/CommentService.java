package com.taskly.service;

import com.taskly.common.error.ApiException;
import com.taskly.common.error.ErrorMessage;
import com.taskly.common.error.ProblemCodes;
import com.taskly.dto.comment.CommentAuthor;
import com.taskly.dto.comment.CommentResponse;
import com.taskly.dto.comment.CreateCommentRequest;
import com.taskly.dto.common.Created;
import com.taskly.dto.search.CommentSearchItem;
import com.taskly.dto.search.SearchQuery;
import com.taskly.entity.Project;
import com.taskly.entity.Task;
import com.taskly.mapper.CommentMapper;
import com.taskly.repository.AttachmentRepository;
import com.taskly.repository.AttachmentRepository.AttachmentRow;
import com.taskly.repository.CommentRepository;
import com.taskly.repository.CommentRepository.CommentRow;
import com.taskly.security.AccessPolicy;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Comment của task / dự án (M8b; plan F1, F3). QUYỀN chỉ đi qua TaskService / ProjectService (→ AccessPolicy): thấy task /
 * dự án thì đọc được comment; ghi (tạo, xoá, reaction) như sửa task — dự án / section lưu trữ → 409. Task đã hoàn thành
 * vẫn comment được. Chỉ tác giả xoá được comment của mình (403). Không có sửa nội dung (Todoist Android không có).
 */
@Service
@Transactional
public class CommentService {

    /** Emoji reaction: sau khi cắt khoảng trắng, 1–16 ký tự UTF-16, không chứa khoảng trắng. */
    static final int MAX_EMOJI_LENGTH = 16;

    private final CommentRepository commentRepository;
    private final AttachmentRepository attachmentRepository;
    private final TaskService taskService;
    private final ProjectService projectService;
    private final AccessPolicy accessPolicy;
    private final NotificationService notificationService;
    private final Clock clock;

    public CommentService(CommentRepository commentRepository, AttachmentRepository attachmentRepository,
                          TaskService taskService, ProjectService projectService, AccessPolicy accessPolicy,
                          NotificationService notificationService, Clock clock) {
        this.commentRepository = commentRepository;
        this.attachmentRepository = attachmentRepository;
        this.taskService = taskService;
        this.projectService = projectService;
        this.accessPolicy = accessPolicy;
        this.notificationService = notificationService;
        this.clock = clock;
    }

    /** Đích của comment: đúng một trong taskId / projectId; workspaceId để kiểm @mention. */
    private record Target(UUID taskId, UUID projectId, UUID workspaceId) {
    }

    // ---- đọc

    @Transactional(readOnly = true)
    public List<CommentResponse> listForTask(UUID userId, UUID taskId) {
        taskService.requireReadable(userId, taskId);
        return toResponses(userId, commentRepository.listByTask(taskId));
    }

    @Transactional(readOnly = true)
    public List<CommentResponse> listForProject(UUID userId, UUID projectId) {
        projectService.requireReadable(userId, projectId);
        return toResponses(userId, commentRepository.listByProject(projectId));
    }

    @Transactional(readOnly = true)
    public List<CommentAuthor> mentionableForTask(UUID userId, UUID taskId) {
        Task task = taskService.requireReadable(userId, taskId);
        return mentionable(task.getWorkspaceId());
    }

    @Transactional(readOnly = true)
    public List<CommentAuthor> mentionableForProject(UUID userId, UUID projectId) {
        Project project = projectService.requireReadable(userId, projectId);
        return mentionable(project.getWorkspace().getId());
    }

    /** Comment đọc được (AttachmentService dùng để kiểm quyền tải file). Không thấy → 404 comment-not-found. */
    @Transactional(readOnly = true)
    public CommentRow requireReadable(UUID userId, UUID commentId) {
        CommentRow row = commentRepository.find(commentId)
                .orElseThrow(() -> ApiException.notFound(ErrorMessage.COMMENT_NOT_FOUND));
        readableTarget(userId, row);
        return row;
    }

    // ---- ghi

    public Created<CommentResponse> createForTask(UUID userId, UUID taskId, CreateCommentRequest request) {
        Optional<Created<CommentResponse>> replay = replayOrConflict(userId, taskId, null, request);
        if (replay.isPresent()) {
            return replay.get();
        }
        Task task = taskService.requireWritable(userId, taskId); // 404 / 409 project-archived | section-archived
        return create(userId, new Target(taskId, null, task.getWorkspaceId()), request);
    }

    public Created<CommentResponse> createForProject(UUID userId, UUID projectId, CreateCommentRequest request) {
        Optional<Created<CommentResponse>> replay = replayOrConflict(userId, null, projectId, request);
        if (replay.isPresent()) {
            return replay.get();
        }
        Project project = projectService.requireWritable(userId, projectId); // 404 / 409 project-archived
        return create(userId, new Target(null, projectId, project.getWorkspace().getId()), request);
    }

    /** Thứ tự: 404 (không thấy) → 403 (không phải tác giả) → 409 (dự án / section lưu trữ). */
    public void delete(UUID userId, UUID commentId) {
        CommentRow row = requireReadable(userId, commentId);
        if (!userId.equals(row.authorId())) {
            throw ApiException.forbidden(ErrorMessage.COMMENT_NOT_AUTHOR);
        }
        writableTarget(userId, row);
        commentRepository.delete(commentId); // cascade: file (→ hàng đợi dọn), reaction, mention, thông báo
    }

    /** Thêm (add = true) / bỏ reaction của chính mình; trả comment sau khi đổi. */
    public CommentResponse react(UUID userId, UUID commentId, String emoji, boolean add) {
        String normalized = normalizeEmoji(emoji);
        CommentRow row = requireReadable(userId, commentId);
        writableTarget(userId, row);
        if (add) {
            commentRepository.addReaction(commentId, userId, normalized, clock.instant());
        } else {
            commentRepository.removeReaction(commentId, userId, normalized);
        }
        return toResponses(userId, List.of(row)).get(0);
    }

    // ---- tìm kiếm (search/objects)

    @Transactional(readOnly = true)
    public List<CommentSearchItem> search(List<UUID> workspaceIds, SearchQuery query, int max) {
        return workspaceIds.isEmpty() ? List.of() : commentRepository.search(workspaceIds, query, max);
    }

    // ---- nội bộ

    private Created<CommentResponse> create(UUID userId, Target target, CreateCommentRequest request) {
        String content = request.content() == null ? "" : request.content().strip();
        if (content.isEmpty() && request.attachmentId() == null) {
            throw ApiException.badRequest(ErrorMessage.COMMENT_EMPTY);
        }
        Set<UUID> mentions = mentionIds(request);
        for (UUID mentioned : mentions) {
            if (!accessPolicy.canRead(mentioned, target.workspaceId())) {
                throw ApiException.badRequest(ErrorMessage.MENTION_INVALID);
            }
        }
        AttachmentRow attachment = null;
        if (request.attachmentId() != null) {
            attachment = attachmentRepository.findForUpdate(request.attachmentId())
                    .filter(row -> row.uploaderId().equals(userId))
                    .orElseThrow(() -> ApiException.notFound(ErrorMessage.ATTACHMENT_NOT_FOUND));
            if (attachment.commentId() != null) {
                throw ApiException.conflict(ErrorMessage.ATTACHMENT_IN_USE);
            }
        }
        UUID commentId = request.id() != null ? request.id() : UUID.randomUUID();
        commentRepository.insert(commentId, target.taskId(), target.projectId(), userId, content, clock.instant());
        if (attachment != null) {
            attachmentRepository.attach(attachment.id(), commentId);
        }
        commentRepository.insertMentions(commentId, mentions);
        for (UUID mentioned : mentions) {
            notificationService.notifyMention(mentioned, userId, commentId, target.taskId(), target.projectId());
        }
        CommentRow row = commentRepository.find(commentId).orElseThrow();
        return Created.fresh(toResponses(userId, List.of(row)).get(0));
    }

    /**
     * `id` client đã có (spec 12.11): cùng tác giả + cùng đích + cùng nội dung → trả bản cũ (200 replay); khác → 409
     * id-conflict. Comment người khác cũng 409 (không lộ nội dung).
     */
    private Optional<Created<CommentResponse>> replayOrConflict(UUID userId, UUID taskId, UUID projectId,
                                                               CreateCommentRequest request) {
        if (request.id() == null) {
            return Optional.empty();
        }
        Optional<CommentRow> existing = commentRepository.find(request.id());
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        CommentRow row = existing.get();
        String content = request.content() == null ? "" : request.content().strip();
        boolean same = userId.equals(row.authorId()) && Objects.equals(taskId, row.taskId())
                && Objects.equals(projectId, row.projectId()) && content.equals(row.content())
                && Objects.equals(request.attachmentId(), row.attachmentId());
        if (!same) {
            throw ApiException.conflict(ErrorMessage.ID_CONFLICT).withCode(ProblemCodes.ID_CONFLICT);
        }
        return Optional.of(Created.replay(toResponses(userId, List.of(row)).get(0)));
    }

    private static Set<UUID> mentionIds(CreateCommentRequest request) {
        Set<UUID> ids = new LinkedHashSet<>();
        if (request.mentionUserIds() != null) {
            request.mentionUserIds().stream().filter(Objects::nonNull).forEach(ids::add);
        }
        return ids;
    }

    /** 404 của task / dự án đổi thành comment-not-found (comment của đích không thấy được = không tồn tại). */
    private void readableTarget(UUID userId, CommentRow row) {
        try {
            if (row.taskId() != null) {
                taskService.requireReadable(userId, row.taskId());
            } else {
                projectService.requireReadable(userId, row.projectId());
            }
        } catch (ApiException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw ApiException.notFound(ErrorMessage.COMMENT_NOT_FOUND);
            }
            throw e;
        }
    }

    private void writableTarget(UUID userId, CommentRow row) {
        if (row.taskId() != null) {
            taskService.requireWritable(userId, row.taskId());
        } else {
            projectService.requireWritable(userId, row.projectId());
        }
    }

    private List<CommentAuthor> mentionable(UUID workspaceId) {
        return commentRepository.workspaceMembers(workspaceId).stream()
                .filter(member -> accessPolicy.canRead(member.id(), workspaceId))
                .map(member -> CommentMapper.author(member.id(), member.name(), member.avatarAt()))
                .toList();
    }

    private List<CommentResponse> toResponses(UUID viewerId, List<CommentRow> rows) {
        List<UUID> ids = rows.stream().map(CommentRow::id).toList();
        var reactions = commentRepository.reactions(ids);
        var mentions = commentRepository.mentions(ids);
        return rows.stream().map(row -> CommentMapper.toResponse(row, viewerId, reactions, mentions)).toList();
    }

    static String normalizeEmoji(String emoji) {
        String value = emoji == null ? "" : emoji.strip();
        if (value.isEmpty() || value.length() > MAX_EMOJI_LENGTH || value.codePoints().anyMatch(Character::isWhitespace)) {
            throw ApiException.badRequest(ErrorMessage.REACTION_EMOJI_INVALID);
        }
        return value;
    }
}
