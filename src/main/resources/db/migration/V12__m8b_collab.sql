-- V12 — M8b (phần không phụ thuộc M8a): comment task / dự án + file, reaction, hộp thông báo + cài đặt, ảnh đại diện.
-- Plan: docs/superpowers/plans/2026-10-27-m8b-chuc-nang.md. Giao việc (tasks.assignee_id) + label dùng chung: đợt sau.

-- Ảnh đại diện (F6): file trên đĩa server (FileStorage), cột chỉ giữ khoá lưu + kiểu + lúc đổi (cache-busting).
ALTER TABLE users
    ADD COLUMN avatar_key          VARCHAR(80),
    ADD COLUMN avatar_content_type VARCHAR(32),
    ADD COLUMN avatar_updated_at   TIMESTAMPTZ,
    ADD CONSTRAINT ck_users_avatar CHECK (
        (avatar_key IS NULL AND avatar_content_type IS NULL AND avatar_updated_at IS NULL)
        OR (avatar_key IS NOT NULL AND avatar_content_type IN ('image/jpeg', 'image/png')
            AND avatar_updated_at IS NOT NULL));

-- Comment (F1): của MỘT task hoặc của MỘT dự án. Comment task chỉ gắn task_id (task chuyển dự án thì comment theo).
-- Xoá task / dự án → comment đi theo (cascade). Tác giả xoá tài khoản → giữ comment, author_id = NULL.
CREATE TABLE comments (
    id         UUID        PRIMARY KEY,
    task_id    UUID        REFERENCES tasks (id) ON DELETE CASCADE,
    project_id UUID        REFERENCES projects (id) ON DELETE CASCADE,
    author_id  UUID        REFERENCES users (id) ON DELETE SET NULL,
    content    TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_comments_target CHECK ((task_id IS NULL) <> (project_id IS NULL)),
    CONSTRAINT ck_comments_content_length CHECK (char_length(content) <= 15000)
);

CREATE INDEX ix_comments_task ON comments (task_id, created_at, id) WHERE task_id IS NOT NULL;
CREATE INDEX ix_comments_project ON comments (project_id, created_at, id) WHERE project_id IS NOT NULL;
CREATE INDEX ix_comments_author ON comments (author_id);
-- Tìm comment (search/objects) dùng f_unaccent(lower(content)) ILIKE như tìm task (V3).
CREATE INDEX ix_comments_content_trgm ON comments USING gin (f_unaccent(lower(content)) gin_trgm_ops);

-- File đính kèm (F2): tải lên trước (comment_id NULL), gắn khi tạo comment; 1 file / comment (UNIQUE comment_id).
CREATE TABLE comment_attachments (
    id           UUID         PRIMARY KEY,
    uploader_id  UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    comment_id   UUID         UNIQUE REFERENCES comments (id) ON DELETE CASCADE,
    file_name    VARCHAR(255) NOT NULL,
    content_type VARCHAR(127) NOT NULL,
    size_bytes   BIGINT       NOT NULL CHECK (size_bytes > 0),
    storage_key  VARCHAR(80)  NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_comment_attachments_unattached ON comment_attachments (created_at) WHERE comment_id IS NULL;

-- Reaction emoji (F3): mỗi người một lần / emoji / comment.
CREATE TABLE comment_reactions (
    comment_id UUID        NOT NULL REFERENCES comments (id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    emoji      VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (comment_id, user_id, emoji)
);

-- Người được @mention trong comment (F3) — để app tô tên; thông báo nằm ở bảng notifications.
CREATE TABLE comment_mentions (
    comment_id UUID NOT NULL REFERENCES comments (id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    PRIMARY KEY (comment_id, user_id)
);

-- Hộp thông báo (F5). Lượt này chỉ COMMENT_MENTION; M8a / đợt sau mở thêm loại.
CREATE TABLE notifications (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(32) NOT NULL,
    actor_id   UUID        REFERENCES users (id) ON DELETE SET NULL,
    comment_id UUID        REFERENCES comments (id) ON DELETE CASCADE,
    task_id    UUID        REFERENCES tasks (id) ON DELETE CASCADE,
    project_id UUID        REFERENCES projects (id) ON DELETE CASCADE,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_notifications_type CHECK (type IN ('COMMENT_MENTION'))
);

CREATE INDEX ix_notifications_user ON notifications (user_id, created_at DESC, id DESC);
CREATE INDEX ix_notifications_user_unread ON notifications (user_id) WHERE read_at IS NULL;

-- Cài đặt Project / Workspace notifications (F5): chỉ lưu hàng đã đổi; không có hàng = mặc định bật.
CREATE TABLE notification_settings (
    user_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    scope   VARCHAR(16) NOT NULL,
    event   VARCHAR(32) NOT NULL,
    enabled BOOLEAN     NOT NULL,
    PRIMARY KEY (user_id, scope, event),
    CONSTRAINT ck_notification_settings_scope CHECK (scope IN ('PROJECT', 'WORKSPACE'))
);

-- Hàng đợi dọn file (F7): mọi đường xoá (comment, task, cây task, dự án, workspace, tài khoản) đi qua FK cascade nên
-- trigger bắt đủ; StoredFileCleanupJob xoá file trên đĩa rồi xoá hàng. Ghi trong cùng transaction với lệnh xoá (rollback
-- thì không dọn).
CREATE TABLE stored_file_deletions (
    storage_key VARCHAR(80) PRIMARY KEY,
    queued_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE FUNCTION f_queue_attachment_file_deletion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO stored_file_deletions (storage_key) VALUES (OLD.storage_key) ON CONFLICT DO NOTHING;
    RETURN NULL;
END;
$$;

CREATE TRIGGER tr_comment_attachments_queue_file
    AFTER DELETE ON comment_attachments
    FOR EACH ROW EXECUTE FUNCTION f_queue_attachment_file_deletion();

CREATE FUNCTION f_queue_avatar_file_deletion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO stored_file_deletions (storage_key) VALUES (OLD.avatar_key) ON CONFLICT DO NOTHING;
    RETURN NULL;
END;
$$;

CREATE TRIGGER tr_users_avatar_replaced
    AFTER UPDATE OF avatar_key ON users
    FOR EACH ROW WHEN (OLD.avatar_key IS NOT NULL AND OLD.avatar_key IS DISTINCT FROM NEW.avatar_key)
    EXECUTE FUNCTION f_queue_avatar_file_deletion();

CREATE TRIGGER tr_users_avatar_deleted
    AFTER DELETE ON users
    FOR EACH ROW WHEN (OLD.avatar_key IS NOT NULL)
    EXECUTE FUNCTION f_queue_avatar_file_deletion();
