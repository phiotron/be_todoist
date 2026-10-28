package com.taskly.repository;

import com.taskly.dto.search.CommentSearchItem;
import com.taskly.dto.search.SearchQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Bảng comments / comment_reactions / comment_mentions (M8b, V12) bằng SQL tay — một câu đọc ra đủ tác giả + file của
 * cả danh sách (như TaskViewRepository). Không kiểm quyền: CommentService kiểm qua TaskService / ProjectService trước.
 */
@Repository
public class CommentRepository {

    /** Một comment + tác giả + file (cột a.* null khi không có file). */
    public record CommentRow(UUID id, UUID taskId, UUID projectId, UUID authorId, String content, Instant createdAt,
                             String authorName, Instant authorAvatarAt, UUID attachmentId, String fileName,
                             String contentType, Long sizeBytes) {
    }

    public record ReactionRow(UUID commentId, String emoji, UUID userId) {
    }

    public record MentionRow(UUID commentId, UUID userId) {
    }

    private static final String SELECT = """
            select c.id, c.task_id, c.project_id, c.author_id, c.content, c.created_at,
                   u.display_name as author_name, u.avatar_updated_at as author_avatar_at,
                   a.id as attachment_id, a.file_name, a.content_type, a.size_bytes
            from comments c
            left join users u on u.id = c.author_id
            left join comment_attachments a on a.comment_id = c.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CommentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, UUID taskId, UUID projectId, UUID authorId, String content, Instant now) {
        jdbc.update("""
                insert into comments (id, task_id, project_id, author_id, content, created_at, updated_at)
                values (:id, :taskId, :projectId, :authorId, :content, :now, :now)
                """, new MapSqlParameterSource()
                .addValue("id", id).addValue("taskId", taskId).addValue("projectId", projectId)
                .addValue("authorId", authorId).addValue("content", content)
                .addValue("now", Timestamp.from(now)));
    }

    public Optional<CommentRow> find(UUID id) {
        return jdbc.query(SELECT + " where c.id = :id", new MapSqlParameterSource("id", id), CommentRepository::map)
                .stream().findFirst();
    }

    /** Comment của task, cũ → mới (Todoist: thứ tự thời gian, không sắp lại được). */
    public List<CommentRow> listByTask(UUID taskId) {
        return jdbc.query(SELECT + " where c.task_id = :id order by c.created_at, c.id",
                new MapSqlParameterSource("id", taskId), CommentRepository::map);
    }

    public List<CommentRow> listByProject(UUID projectId) {
        return jdbc.query(SELECT + " where c.project_id = :id order by c.created_at, c.id",
                new MapSqlParameterSource("id", projectId), CommentRepository::map);
    }

    public void delete(UUID id) {
        jdbc.update("delete from comments where id = :id", new MapSqlParameterSource("id", id));
    }

    // ---- reaction

    public List<ReactionRow> reactions(Collection<UUID> commentIds) {
        if (commentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                select comment_id, emoji, user_id from comment_reactions where comment_id in (:ids)
                order by comment_id, created_at, emoji, user_id
                """, new MapSqlParameterSource("ids", commentIds), (rs, n) -> new ReactionRow(
                rs.getObject("comment_id", UUID.class), rs.getString("emoji"), rs.getObject("user_id", UUID.class)));
    }

    /** Thêm lại cùng emoji = giữ nguyên (không lỗi). */
    public void addReaction(UUID commentId, UUID userId, String emoji, Instant now) {
        jdbc.update("""
                insert into comment_reactions (comment_id, user_id, emoji, created_at)
                values (:commentId, :userId, :emoji, :now) on conflict do nothing
                """, new MapSqlParameterSource().addValue("commentId", commentId).addValue("userId", userId)
                .addValue("emoji", emoji).addValue("now", Timestamp.from(now)));
    }

    public void removeReaction(UUID commentId, UUID userId, String emoji) {
        jdbc.update("delete from comment_reactions where comment_id = :commentId and user_id = :userId and emoji = :emoji",
                new MapSqlParameterSource().addValue("commentId", commentId).addValue("userId", userId)
                        .addValue("emoji", emoji));
    }

    // ---- mention

    public void insertMentions(UUID commentId, Collection<UUID> userIds) {
        for (UUID userId : userIds) {
            jdbc.update("insert into comment_mentions (comment_id, user_id) values (:c, :u) on conflict do nothing",
                    new MapSqlParameterSource().addValue("c", commentId).addValue("u", userId));
        }
    }

    public List<MentionRow> mentions(Collection<UUID> commentIds) {
        if (commentIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("select comment_id, user_id from comment_mentions where comment_id in (:ids) order by user_id",
                new MapSqlParameterSource("ids", commentIds), (rs, n) -> new MentionRow(
                        rs.getObject("comment_id", UUID.class), rs.getObject("user_id", UUID.class)));
    }

    // ---- người có thể được @mention

    public record MemberRow(UUID id, String name, Instant avatarAt) {
    }

    /**
     * Thành viên workspace của dự án (Mức 1: thành viên workspace xem được mọi dự án). CommentService lọc thêm qua
     * AccessPolicy.canRead — M8a đổi nguồn danh sách (project_members) ở đây.
     */
    public List<MemberRow> workspaceMembers(UUID workspaceId) {
        return jdbc.query("""
                select u.id, u.display_name, u.avatar_updated_at from workspace_members m
                join users u on u.id = m.user_id where m.workspace_id = :workspaceId
                order by lower(u.display_name), u.id
                """, new MapSqlParameterSource("workspaceId", workspaceId), (rs, n) -> new MemberRow(
                rs.getObject("id", UUID.class), rs.getString("display_name"), instant(rs, "avatar_updated_at")));
    }

    // ---- tìm kiếm (search/objects, spec 12.6 "M8 + comments")

    /**
     * Comment khớp mọi từ của query (không phân biệt hoa thường / dấu), trong các workspace đọc được, loại dự án lưu
     * trữ. Comment task lấy dự án qua task; comment dự án lấy thẳng. Mới trước.
     */
    public List<CommentSearchItem> search(List<UUID> workspaceIds, SearchQuery query, int max) {
        MapSqlParameterSource params = new MapSqlParameterSource("workspaceIds", workspaceIds).addValue("max", max);
        StringBuilder terms = new StringBuilder();
        List<String> patterns = query.likePatterns();
        for (int i = 0; i < patterns.size(); i++) {
            params.addValue("term" + i, patterns.get(i));
            terms.append(" and f_unaccent(lower(c.content)) ilike f_unaccent(lower(:term").append(i)
                    .append(")) escape '\\'");
        }
        String sql = """
                select c.id, c.task_id, p.id as project_id, p.name as project_name, t.content as task_content, c.content
                from comments c
                left join tasks t on t.id = c.task_id
                join projects p on p.id = coalesce(t.project_id, c.project_id)
                where p.workspace_id in (:workspaceIds) and p.archived_at is null
                """ + terms + " order by c.created_at desc, c.id desc limit :max";
        return jdbc.query(sql, params, (rs, n) -> new CommentSearchItem(rs.getObject("id", UUID.class),
                rs.getObject("task_id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("project_name"), rs.getString("task_content"), rs.getString("content")));
    }

    private static CommentRow map(ResultSet rs, int rowNum) throws SQLException {
        long size = rs.getLong("size_bytes");
        Long sizeBytes = rs.wasNull() ? null : size;
        return new CommentRow(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("author_id", UUID.class),
                rs.getString("content"), instant(rs, "created_at"), rs.getString("author_name"),
                instant(rs, "author_avatar_at"), rs.getObject("attachment_id", UUID.class), rs.getString("file_name"),
                rs.getString("content_type"), sizeBytes);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
