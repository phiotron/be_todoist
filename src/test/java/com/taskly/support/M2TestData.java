package com.taskly.support;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dữ liệu mẫu cho test M2, chèn thẳng bằng SQL (bỏ qua giới hạn Pro và luồng API của M1).
 * Mọi timestamp mặc định lấy từ Clock của test. Cột theo spec mục 10 (A2 của plan M2).
 */
public class M2TestData {

    /** sort_order tăng dần toàn suite → không trùng giữa các test. */
    private static final AtomicLong NEXT_ORDER = new AtomicLong(1_000_000L);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public M2TestData(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public static long nextSortOrder() {
        return NEXT_ORDER.addAndGet(1024);
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public UUID workspaceOf(UUID userId) {
        return jdbc.queryForObject(
                "select id from workspaces where owner_id = ? and type = 'PERSONAL'", UUID.class, userId);
    }

    public UUID inboxOf(UUID userId) {
        return jdbc.queryForObject("select p.id from projects p join workspaces w on w.id = p.workspace_id "
                + "where w.owner_id = ? and p.is_inbox", UUID.class, userId);
    }

    public UUID project(UUID userId, String name) {
        return project(userId, null, name);
    }

    public UUID project(UUID userId, UUID parentId, String name) {
        return project(userId, parentId, name, nextSortOrder());
    }

    public UUID project(UUID userId, UUID parentId, String name, long sortOrder) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into projects (id, workspace_id, parent_id, name, description, color, is_inbox, "
                        + "view_style, sort_order, created_at, updated_at) "
                        + "values (?, ?, ?, ?, '', 'charcoal', false, 'LIST', ?, ?, ?)",
                id, workspaceOf(userId), parentId, name, sortOrder, now(), now());
        return id;
    }

    public void archiveProject(UUID projectId) {
        jdbc.update("update projects set archived_at = ? where id = ?", now(), projectId);
    }

    public UUID section(UUID projectId, String name) {
        return section(projectId, name, nextSortOrder());
    }

    public UUID section(UUID projectId, String name, long sortOrder) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into sections (id, project_id, name, description, sort_order, created_at, updated_at) "
                + "values (?, ?, ?, '', ?, ?, ?)", id, projectId, name, sortOrder, now(), now());
        return id;
    }

    public void archiveSection(UUID sectionId) {
        jdbc.update("update sections set archived_at = ? where id = ?", now(), sectionId);
    }

    public UUID task(UUID projectId, String content) {
        return insert(TaskSpec.of(projectId, content));
    }

    public UUID insert(TaskSpec spec) {
        UUID id = UUID.randomUUID();
        OffsetDateTime created = spec.createdAt() == null ? now() : at(spec.createdAt());
        // tasks.workspace_id (phi chuẩn, V2 bản 3.1) = workspace của dự án; due_day / due_sort là cột sinh.
        jdbc.update("insert into tasks (id, workspace_id, project_id, section_id, parent_id, content, description, "
                        + "priority, due_date, due_datetime, deadline_date, sort_order, completed_at, version, "
                        + "created_at, updated_at) values (?, (select workspace_id from projects where id = ?), "
                        + "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)",
                id, spec.projectId(), spec.projectId(), spec.sectionId(), spec.parentId(), spec.content(),
                spec.description(),
                spec.priority(), spec.dueDate(), spec.dueDatetime(), spec.deadlineDate(),
                spec.sortOrder() == null ? nextSortOrder() : spec.sortOrder(), at(spec.completedAt()),
                created, created);
        return id;
    }

    public void deleteTask(UUID taskId) {
        jdbc.update("delete from tasks where id = ?", taskId);
    }

    public UUID label(UUID userId, String name) {
        return label(userId, name, nextSortOrder());
    }

    public UUID label(UUID userId, String name, long sortOrder) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into labels (id, user_id, name, color, sort_order, created_at, updated_at) "
                + "values (?, ?, ?, 'charcoal', ?, ?, ?)", id, userId, name, sortOrder, now(), now());
        return id;
    }

    public void tag(UUID taskId, UUID... labelIds) {
        for (UUID labelId : labelIds) {
            jdbc.update("insert into task_labels (task_id, label_id) values (?, ?)", taskId, labelId);
        }
    }

    public UUID filter(UUID userId, String name, String query) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into filters (id, user_id, name, query, color, sort_order, created_at, updated_at) "
                + "values (?, ?, ?, ?, 'charcoal', ?, ?, ?)", id, userId, name, query, nextSortOrder(), now(), now());
        return id;
    }

    /** Một Display đã lưu (DATE / ASC / NONE) — filterLabelId có thể null. */
    public void viewOption(UUID userId, String viewKey, UUID filterLabelId) {
        jdbc.update("insert into view_options (id, user_id, view_key, sort_by, sort_direction, group_by, "
                        + "filter_label_id, show_completed, created_at, updated_at) "
                        + "values (?, ?, ?, 'DATE', 'ASC', 'NONE', ?, false, ?, ?)",
                UUID.randomUUID(), userId, viewKey, filterLabelId, now(), now());
    }

    public void setTimeZone(UUID userId, String timeZone) {
        jdbc.update("update users set time_zone = ? where id = ?", timeZone, userId);
    }

    /** Dòng task cần chèn; dùng wither để đổi từng trường. sortOrder / createdAt null = tự cấp. */
    public record TaskSpec(UUID projectId, UUID sectionId, UUID parentId, String content, String description,
                           int priority, LocalDate dueDate, LocalDateTime dueDatetime, LocalDate deadlineDate,
                           Long sortOrder, Instant completedAt, Instant createdAt) {

        public static TaskSpec of(UUID projectId, String content) {
            return new TaskSpec(projectId, null, null, content, "", 4, null, null, null, null, null, null);
        }

        public TaskSpec section(UUID v) {
            return new TaskSpec(projectId, v, parentId, content, description, priority, dueDate, dueDatetime,
                    deadlineDate, sortOrder, completedAt, createdAt);
        }

        public TaskSpec parent(UUID v) {
            return new TaskSpec(projectId, sectionId, v, content, description, priority, dueDate, dueDatetime,
                    deadlineDate, sortOrder, completedAt, createdAt);
        }

        public TaskSpec description(String v) {
            return new TaskSpec(projectId, sectionId, parentId, content, v, priority, dueDate, dueDatetime,
                    deadlineDate, sortOrder, completedAt, createdAt);
        }

        public TaskSpec priority(int v) {
            return new TaskSpec(projectId, sectionId, parentId, content, description, v, dueDate, dueDatetime,
                    deadlineDate, sortOrder, completedAt, createdAt);
        }

        public TaskSpec dueDate(LocalDate v) {
            return new TaskSpec(projectId, sectionId, parentId, content, description, priority, v, null,
                    deadlineDate, sortOrder, completedAt, createdAt);
        }

        public TaskSpec dueDatetime(LocalDateTime v) {
            return new TaskSpec(projectId, sectionId, parentId, content, description, priority, null, v,
                    deadlineDate, sortOrder, completedAt, createdAt);
        }

        public TaskSpec deadline(LocalDate v) {
            return new TaskSpec(projectId, sectionId, parentId, content, description, priority, dueDate,
                    dueDatetime, v, sortOrder, completedAt, createdAt);
        }

        public TaskSpec sortOrder(long v) {
            return new TaskSpec(projectId, sectionId, parentId, content, description, priority, dueDate,
                    dueDatetime, deadlineDate, v, completedAt, createdAt);
        }

        public TaskSpec completedAt(Instant v) {
            return new TaskSpec(projectId, sectionId, parentId, content, description, priority, dueDate,
                    dueDatetime, deadlineDate, sortOrder, v, createdAt);
        }

        public TaskSpec createdAt(Instant v) {
            return new TaskSpec(projectId, sectionId, parentId, content, description, priority, dueDate,
                    dueDatetime, deadlineDate, sortOrder, completedAt, v);
        }
    }
}
