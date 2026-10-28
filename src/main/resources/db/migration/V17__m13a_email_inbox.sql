-- V17 — Mốc M13a (email → task). Số migration chốt ở contract M6–M14: M13a = V17.
-- 1) email_inboxes: địa chỉ nhận thư của MỘT người dùng cho MỘT dự án (kể cả Inbox) hoặc MỘT task (như Todoist: địa chỉ
--    thuộc người dùng). Phần trước @ là token ngẫu nhiên 128 bit — bí mật (ai biết thì thêm được task):
--    token_hash = SHA-256 hex (tra cứu khi thư tới), token_cipher = AES-GCM (khoá con từ TOTP_ENCRYPTION_KEY, aad = id)
--    để hiện lại địa chỉ cho chủ. KHÔNG lưu token rõ. Tạo lại địa chỉ = ghi đè hai cột này (địa chỉ cũ hết hiệu lực).
-- 2) email_inbound_messages: chống xử lý lại cùng một thư (message-id) cho cùng một địa chỉ (nhà cung cấp gửi lại
--    webhook). Chèn trong CÙNG transaction với việc tạo / sửa task → lỗi thì rollback, gửi lại được.

CREATE TABLE email_inboxes (
    id           UUID        PRIMARY KEY,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id   UUID        REFERENCES projects (id) ON DELETE CASCADE,
    task_id      UUID        REFERENCES tasks (id) ON DELETE CASCADE,
    token_hash   VARCHAR(64) NOT NULL,
    token_cipher TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_email_inboxes_token_hash UNIQUE (token_hash),
    -- mỗi người một địa chỉ cho mỗi dự án / task (NULL không đụng nhau nên hai ràng buộc độc lập)
    CONSTRAINT uq_email_inboxes_user_project UNIQUE (user_id, project_id),
    CONSTRAINT uq_email_inboxes_user_task UNIQUE (user_id, task_id),
    CONSTRAINT ck_email_inboxes_one_target CHECK ((project_id IS NULL) <> (task_id IS NULL))
);

-- FK cascade khi xoá dự án / task cần chỉ mục theo cột đích
CREATE INDEX ix_email_inboxes_project_id ON email_inboxes (project_id);
CREATE INDEX ix_email_inboxes_task_id ON email_inboxes (task_id);

CREATE TABLE email_inbound_messages (
    inbox_id        UUID        NOT NULL REFERENCES email_inboxes (id) ON DELETE CASCADE,
    -- SHA-256 hex của Message-ID (độ dài tuỳ ý, có thể chứa thông tin người gửi → chỉ giữ hash)
    message_id_hash VARCHAR(64) NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_email_inbound_messages PRIMARY KEY (inbox_id, message_id_hash)
);

CREATE INDEX ix_email_inbound_messages_received_at ON email_inbound_messages (received_at);
