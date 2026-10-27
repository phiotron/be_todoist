package com.taskly.support;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Chèn dữ liệu mẫu thẳng bằng SQL (không qua API) để test một endpoint không phụ thuộc endpoint khác.
 * Mọi timestamp lấy từ Clock của test. dueDatetime là giờ floating (LocalDateTime → cột TIMESTAMP).
 */
public class TestData {

    /** sort_order tăng dần toàn suite → không trùng giữa các test. */
    private static final AtomicLong NEXT_ORDER = new AtomicLong();

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TestData(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public static long nextSortOrder() {
        return NEXT_ORDER.addAndGet(1024);
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    public UUID workspaceOf(UUID userId) {
        return jdbc.queryForObject(
                "select id from workspaces where owner_id = ? and type = 'PERSONAL'", UUID.class, userId);
    }

    public UUID inboxOf(UUID userId) {
        return jdbc.queryForObject("select p.id from projects p join workspaces w on w.id = p.workspace_id "
                + "where w.owner_id = ? and p.is_inbox", UUID.class, userId);
    }

    /** Dự án gốc (không cha), màu charcoal. */
    public UUID project(UUID userId, String name) {
        return childProject(userId, null, name);
    }

    /** Dự án con của parentId (null = gốc). Không kiểm độ sâu — test tự giữ ≤ 3 cấp khi cần. */
    public UUID childProject(UUID userId, UUID parentId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into projects (id, workspace_id, parent_id, name, description, color, is_inbox, view_style, "
                        + "sort_order, created_at, updated_at) values (?, ?, ?, ?, '', 'charcoal', false, 'LIST', ?, ?, ?)",
                id, workspaceOf(userId), parentId, name, nextSortOrder(), now(), now());
        return id;
    }

    /** Chỉ đánh dấu ĐÚNG dòng này (không lan sang dự án con) — test lưu trữ kèm con đi qua API. */
    public void archiveProject(UUID projectId) {
        jdbc.update("update projects set archived_at = ? where id = ?", now(), projectId);
    }

    public void deleteProject(UUID projectId) {
        jdbc.update("delete from projects where id = ?", projectId);
    }

    public UUID section(UUID projectId, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into sections (id, project_id, name, description, sort_order, created_at, updated_at) "
                + "values (?, ?, ?, '', ?, ?, ?)", id, projectId, name, nextSortOrder(), now(), now());
        return id;
    }

    /** Chỉ đặt archived_at (KHÔNG hoàn thành task) — test lưu trữ section đầy đủ đi qua API (Task 18). */
    public void archiveSection(UUID sectionId) {
        jdbc.update("update sections set archived_at = ? where id = ?", now(), sectionId);
    }

    public void deleteSection(UUID sectionId) {
        jdbc.update("delete from sections where id = ?", sectionId);
    }

    public UUID task(UUID userId, UUID projectId, String content) {
        return insert(TaskRow.of(userId, projectId, content));
    }

    public UUID insert(TaskRow row) {
        UUID id = UUID.randomUUID();
        // workspace_id (NOT NULL, phi chuẩn) lấy từ dự án; tham số thứ 2 và 3 đều là projectId (subselect + cột project_id)
        jdbc.update("insert into tasks (id, workspace_id, project_id, section_id, parent_id, creator_id, content, "
                        + "priority, due_date, due_datetime, duration_minutes, deadline_date, sort_order, completed_at, "
                        + "created_at, updated_at) values (?, (select workspace_id from projects where id = ?)"
                        + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, row.projectId(), row.projectId(), row.sectionId(), row.parentId(), row.creatorId(), row.content(),
                row.priority(),
                row.dueDate(), row.dueDatetime() == null ? null : Timestamp.valueOf(row.dueDatetime()),
                row.durationMinutes(), row.deadlineDate(),
                row.sortOrder() == null ? nextSortOrder() : row.sortOrder(),
                row.completedAt() == null ? null : Timestamp.from(row.completedAt()), now(), now());
        return id;
    }

    public void deleteTask(UUID taskId) {
        jdbc.update("delete from tasks where id = ?", taskId);
    }

    // ---- Đọc lại trạng thái task (test version / native UPDATE / batch Undo — spec 5.6, 5.1) ----

    public long taskVersion(UUID taskId) {
        return jdbc.queryForObject("select version from tasks where id = ?", Long.class, taskId);
    }

    public Instant taskUpdatedAt(UUID taskId) {
        return jdbc.queryForObject("select updated_at from tasks where id = ?", Timestamp.class, taskId).toInstant();
    }

    public long taskSortOrder(UUID taskId) {
        return jdbc.queryForObject("select sort_order from tasks where id = ?", Long.class, taskId);
    }

    public UUID taskWorkspace(UUID taskId) {
        return jdbc.queryForObject("select workspace_id from tasks where id = ?", UUID.class, taskId);
    }

    /** completion_batch_id hiện tại của task (null = chưa xong hoặc xong từ trước khi có batch). */
    public UUID taskCompletionBatch(UUID taskId) {
        return jdbc.queryForObject("select completion_batch_id from tasks where id = ?", UUID.class, taskId);
    }

    public void favorite(UUID userId, String targetType, UUID targetId) {
        jdbc.update("insert into favorites (id, user_id, target_type, target_id, created_at, updated_at) "
                + "values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), userId, targetType, targetId, now(), now());
    }

    public void setTimeZone(UUID userId, String timeZone) {
        jdbc.update("update users set time_zone = ? where id = ?", timeZone, userId);
    }

    /** Dòng task cần chèn; dùng wither để đổi từng trường. sortOrder null = tự cấp. */
    public record TaskRow(UUID creatorId, UUID projectId, UUID sectionId, UUID parentId, String content,
                          int priority, LocalDate dueDate, LocalDateTime dueDatetime, Integer durationMinutes,
                          LocalDate deadlineDate, Long sortOrder, Instant completedAt) {

        public static TaskRow of(UUID creatorId, UUID projectId, String content) {
            return new TaskRow(creatorId, projectId, null, null, content, 4, null, null, null, null, null, null);
        }

        public TaskRow section(UUID value) {
            return new TaskRow(creatorId, projectId, value, parentId, content, priority, dueDate, dueDatetime,
                    durationMinutes, deadlineDate, sortOrder, completedAt);
        }

        public TaskRow parent(UUID value) {
            return new TaskRow(creatorId, projectId, sectionId, value, content, priority, dueDate, dueDatetime,
                    durationMinutes, deadlineDate, sortOrder, completedAt);
        }

        public TaskRow priority(int value) {
            return new TaskRow(creatorId, projectId, sectionId, parentId, content, value, dueDate, dueDatetime,
                    durationMinutes, deadlineDate, sortOrder, completedAt);
        }

        public TaskRow dueDate(LocalDate value) {
            return new TaskRow(creatorId, projectId, sectionId, parentId, content, priority, value, dueDatetime,
                    durationMinutes, deadlineDate, sortOrder, completedAt);
        }

        public TaskRow dueDatetime(LocalDateTime value) {
            return new TaskRow(creatorId, projectId, sectionId, parentId, content, priority, dueDate, value,
                    durationMinutes, deadlineDate, sortOrder, completedAt);
        }

        public TaskRow durationMinutes(int value) {
            return new TaskRow(creatorId, projectId, sectionId, parentId, content, priority, dueDate, dueDatetime,
                    value, deadlineDate, sortOrder, completedAt);
        }

        public TaskRow deadlineDate(LocalDate value) {
            return new TaskRow(creatorId, projectId, sectionId, parentId, content, priority, dueDate, dueDatetime,
                    durationMinutes, value, sortOrder, completedAt);
        }

        public TaskRow sortOrder(long value) {
            return new TaskRow(creatorId, projectId, sectionId, parentId, content, priority, dueDate, dueDatetime,
                    durationMinutes, deadlineDate, value, completedAt);
        }

        public TaskRow completedAt(Instant value) {
            return new TaskRow(creatorId, projectId, sectionId, parentId, content, priority, dueDate, dueDatetime,
                    durationMinutes, deadlineDate, sortOrder, value);
        }
    }
}
