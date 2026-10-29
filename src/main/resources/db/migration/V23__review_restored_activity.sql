-- Hoàn tác "Trả lại" việc chờ duyệt: thêm sự kiện nhật ký REVIEW_RESTORED.
ALTER TABLE activity_log DROP CONSTRAINT ck_activity_log_event_type;
ALTER TABLE activity_log ADD CONSTRAINT ck_activity_log_event_type CHECK (event_type IN
    ('ADDED', 'UPDATED', 'COMPLETED', 'UNCOMPLETED', 'DELETED', 'ARCHIVED', 'UNARCHIVED',
     'REVIEW_REQUESTED', 'REVIEW_APPROVED', 'REVIEW_REJECTED', 'REVIEW_CANCELLED', 'REVIEW_RESTORED'));
