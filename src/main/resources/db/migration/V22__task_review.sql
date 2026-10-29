-- Chờ duyệt (review): Member / Guest tick hoàn thành ở dự án NHÓM → PENDING (vẫn mở, completed_at NULL) tới khi Admin
-- duyệt (hoàn thành thật) hoặc trả lại. Dự án cá nhân không dùng.
ALTER TABLE tasks
    ADD COLUMN review_status       VARCHAR(16) NOT NULL DEFAULT 'NONE',
    ADD COLUMN review_requested_by UUID        REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN review_requested_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_tasks_review_status CHECK (review_status IN ('NONE', 'PENDING'));

CREATE INDEX ix_tasks_review_pending ON tasks (project_id) WHERE review_status = 'PENDING';

-- Thông báo mới cho luồng duyệt (gửi Admin khi có yêu cầu, gửi người yêu cầu khi được duyệt / trả lại).
ALTER TABLE notifications DROP CONSTRAINT ck_notifications_type;
ALTER TABLE notifications ADD CONSTRAINT ck_notifications_type CHECK (type IN
    ('COMMENT_MENTION', 'REVIEW_REQUESTED', 'REVIEW_APPROVED', 'REVIEW_REJECTED'));

-- Nhật ký: review_requested | review_approved | review_rejected | review_cancelled (tên enum, in hoa).
ALTER TABLE activity_log DROP CONSTRAINT ck_activity_log_event_type;
ALTER TABLE activity_log ADD CONSTRAINT ck_activity_log_event_type CHECK (event_type IN
    ('ADDED', 'UPDATED', 'COMPLETED', 'UNCOMPLETED', 'DELETED', 'ARCHIVED', 'UNARCHIVED',
     'REVIEW_REQUESTED', 'REVIEW_APPROVED', 'REVIEW_REJECTED', 'REVIEW_CANCELLED'));
