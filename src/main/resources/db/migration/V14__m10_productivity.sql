-- V14 — Mốc M10 (năng suất & lịch sử). Plan: docs/superpowers/plans/2026-10-27-m10-chuc-nang.md.
-- Cài đặt Productivity (me/settings), Karma, kỳ nghỉ (Vacation mode), Activity log.

-- Cài đặt Productivity theo tài khoản. days_off = ngày ISO (1 = Thứ Hai … 7 = Chủ nhật) nối bằng dấu phẩy, tăng dần,
-- tối đa 6 ngày ('' = không nghỉ ngày nào). Mặc định Thứ Bảy + Chủ nhật [CXM].
ALTER TABLE users
    ADD COLUMN daily_goal        INTEGER     NOT NULL DEFAULT 5,
    ADD COLUMN weekly_goal       INTEGER     NOT NULL DEFAULT 30,
    ADD COLUMN days_off          VARCHAR(16) NOT NULL DEFAULT '6,7',
    ADD COLUMN vacation_mode     BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN goal_celebrations BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN karma_enabled     BOOLEAN     NOT NULL DEFAULT TRUE;

ALTER TABLE users
    ADD CONSTRAINT ck_users_daily_goal CHECK (daily_goal BETWEEN 1 AND 200),
    ADD CONSTRAINT ck_users_weekly_goal CHECK (weekly_goal BETWEEN 1 AND 1000),
    ADD CONSTRAINT ck_users_days_off CHECK (days_off ~ '^([1-7](,[1-7]){0,5})?$');

-- Kỳ nghỉ: bật Vacation mode mở một kỳ (ended_on null), tắt thì đóng kỳ. Ngày trong kỳ không cắt streak, không bị phạt
-- Karma quá hạn. Ngày theo múi giờ tài khoản lúc bật / tắt.
CREATE TABLE vacation_periods (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    started_on DATE        NOT NULL,
    ended_on   DATE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_vacation_periods_range CHECK (ended_on IS NULL OR ended_on >= started_on)
);
CREATE INDEX ix_vacation_periods_user ON vacation_periods (user_id, started_on);
-- Mỗi người tối đa một kỳ đang mở.
CREATE UNIQUE INDEX uq_vacation_periods_open ON vacation_periods (user_id) WHERE ended_on IS NULL;

-- Karma (quyết định tạm của Taskly — công thức Todoist không công bố). occurred_on = ngày tính điểm theo múi giờ tài khoản.
-- ref_date: TASK_UNCOMPLETED = ngày của lần cộng bị huỷ; DAILY_GOAL = ngày; WEEKLY_GOAL = ngày đầu tuần;
-- OVERDUE_PENALTY = due_day của task. task_id KHÔNG có FK: task bị xoá vẫn giữ lịch sử điểm.
CREATE TABLE karma_events (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    reason      VARCHAR(24) NOT NULL,
    points      INTEGER     NOT NULL,
    task_id     UUID,
    occurred_on DATE        NOT NULL,
    ref_date    DATE,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_karma_events_reason CHECK (reason IN
        ('TASK_COMPLETED', 'TASK_UNCOMPLETED', 'DAILY_GOAL', 'WEEKLY_GOAL', 'OVERDUE_PENALTY'))
);
CREATE INDEX ix_karma_events_user_day ON karma_events (user_id, occurred_on);
CREATE INDEX ix_karma_events_task ON karma_events (task_id, created_at DESC) WHERE task_id IS NOT NULL;
CREATE UNIQUE INDEX uq_karma_events_goal ON karma_events (user_id, reason, ref_date)
    WHERE reason IN ('DAILY_GOAL', 'WEEKLY_GOAL');
CREATE UNIQUE INDEX uq_karma_events_overdue ON karma_events (user_id, task_id, ref_date)
    WHERE reason = 'OVERDUE_PENALTY';

-- Activity log: ghi cùng transaction với thao tác. KHÔNG FK tới tasks / projects / sections (dòng của đối tượng đã xoá vẫn
-- đọc được); object_name / project_name = ảnh chụp tên lúc ghi. Quyền đọc theo workspace_id (xoá workspace → xoá dòng).
CREATE TABLE activity_log (
    id           UUID         PRIMARY KEY,
    workspace_id UUID         NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    actor_id     UUID         REFERENCES users (id) ON DELETE SET NULL,
    object_type  VARCHAR(16)  NOT NULL,
    object_id    UUID         NOT NULL,
    event_type   VARCHAR(16)  NOT NULL,
    project_id   UUID,
    object_name  VARCHAR(2048) NOT NULL, -- tên section tối đa 2048 ký tự (task 500, dự án 120)
    project_name VARCHAR(120),
    created_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_activity_log_object_type CHECK (object_type IN ('TASK', 'PROJECT', 'SECTION')),
    CONSTRAINT ck_activity_log_event_type CHECK (event_type IN
        ('ADDED', 'UPDATED', 'COMPLETED', 'UNCOMPLETED', 'DELETED', 'ARCHIVED', 'UNARCHIVED'))
);
CREATE INDEX ix_activity_log_workspace ON activity_log (workspace_id, created_at DESC, id DESC);
CREATE INDEX ix_activity_log_project ON activity_log (project_id, created_at DESC, id DESC);
CREATE INDEX ix_activity_log_object ON activity_log (object_id, created_at DESC, id DESC);
CREATE INDEX ix_activity_log_actor ON activity_log (actor_id);
