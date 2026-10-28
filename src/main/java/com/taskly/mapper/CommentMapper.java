package com.taskly.mapper;

import com.taskly.dto.comment.AttachmentResponse;
import com.taskly.dto.comment.CommentAuthor;
import com.taskly.dto.comment.CommentResponse;
import com.taskly.dto.comment.ReactionSummary;
import com.taskly.repository.AttachmentRepository.AttachmentRow;
import com.taskly.repository.CommentRepository.CommentRow;
import com.taskly.repository.CommentRepository.MentionRow;
import com.taskly.repository.CommentRepository.ReactionRow;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Dòng SQL comment / file → DTO (M8b). */
public final class CommentMapper {

    /** Kiểu ảnh app hiện thu nhỏ được. */
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    private CommentMapper() {
    }

    public static CommentAuthor author(UUID id, String name, Instant avatarUpdatedAt) {
        return id == null ? null : new CommentAuthor(id, name, UserMapper.avatarUrl(id, avatarUpdatedAt));
    }

    public static AttachmentResponse attachment(AttachmentRow row) {
        return new AttachmentResponse(row.id(), row.fileName(), row.contentType(), row.sizeBytes(),
                isImage(row.contentType()));
    }

    public static boolean isImage(String contentType) {
        return contentType != null && IMAGE_TYPES.contains(contentType);
    }

    /** reactions / mentions: mọi dòng của các comment đang dựng (lọc theo comment ở đây). Thứ tự emoji = lần thả đầu. */
    public static CommentResponse toResponse(CommentRow row, UUID viewerId, List<ReactionRow> reactions,
                                             List<MentionRow> mentions) {
        AttachmentResponse attachment = row.attachmentId() == null ? null
                : new AttachmentResponse(row.attachmentId(), row.fileName(), row.contentType(), row.sizeBytes(),
                isImage(row.contentType()));
        Map<String, List<UUID>> byEmoji = new LinkedHashMap<>();
        for (ReactionRow reaction : reactions) {
            if (reaction.commentId().equals(row.id())) {
                byEmoji.computeIfAbsent(reaction.emoji(), e -> new ArrayList<>()).add(reaction.userId());
            }
        }
        List<ReactionSummary> summaries = byEmoji.entrySet().stream()
                .map(e -> new ReactionSummary(e.getKey(), e.getValue().size(), e.getValue().contains(viewerId),
                        List.copyOf(e.getValue())))
                .toList();
        List<UUID> mentionIds = mentions.stream().filter(m -> m.commentId().equals(row.id()))
                .map(MentionRow::userId).toList();
        return new CommentResponse(row.id(), row.taskId(), row.projectId(),
                author(row.authorId(), row.authorName(), row.authorAvatarAt()), row.content(), attachment, summaries,
                mentionIds, viewerId.equals(row.authorId()), row.createdAt());
    }
}
