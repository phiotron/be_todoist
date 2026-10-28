-- V18 — Mốc M13b (đăng nhập Apple / Facebook + Calendar). Số cố định theo contract M6–M14 (V18 dành cho M13b).
-- 1) user_identities: thêm nhà cung cấp APPLE, FACEBOOK; email cho phép null (Facebook có thể không cấp email).
-- 2) calendar_connections: MỘT kết nối lịch / user (Google hoặc Outlook), token OAuth mã hoá bằng SecretCipher.
-- 3) calendar_task_links: task ↔ sự kiện đã đẩy sang lịch "Taskly". task_id KHÔNG có FK: task bị xoá thì lần sync sau
--    vẫn còn event_id để xoá sự kiện bên nhà cung cấp rồi mới xoá dòng.
-- 4) calendar_feeds: link iCal theo tài khoản (project_id null) hoặc theo dự án; token lưu SHA-256 (tra cứu) + bản mã.

ALTER TABLE user_identities DROP CONSTRAINT ck_user_identities_provider;
ALTER TABLE user_identities ADD CONSTRAINT ck_user_identities_provider
    CHECK (provider IN ('GOOGLE', 'APPLE', 'FACEBOOK'));
ALTER TABLE user_identities ALTER COLUMN email DROP NOT NULL;

CREATE TABLE calendar_connections (
    id                 UUID         PRIMARY KEY,
    user_id            UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider           VARCHAR(16)  NOT NULL,
    account_email      VARCHAR(254),
    access_token_enc   TEXT         NOT NULL,
    refresh_token_enc  TEXT,
    token_expires_at   TIMESTAMPTZ,
    show_events        BOOLEAN      NOT NULL DEFAULT TRUE,
    sync_tasks         BOOLEAN      NOT NULL DEFAULT FALSE,
    taskly_calendar_id VARCHAR(512),
    needs_reauth       BOOLEAN      NOT NULL DEFAULT FALSE,
    last_synced_at     TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    -- chỉ nối 1 nhà cung cấp / 1 tài khoản (như Todoist)
    CONSTRAINT uq_calendar_connections_user UNIQUE (user_id),
    CONSTRAINT ck_calendar_connections_provider CHECK (provider IN ('GOOGLE', 'OUTLOOK'))
);

CREATE TABLE calendar_task_links (
    id            UUID         PRIMARY KEY,
    connection_id UUID         NOT NULL REFERENCES calendar_connections (id) ON DELETE CASCADE,
    task_id       UUID         NOT NULL,
    event_id      VARCHAR(512) NOT NULL,
    fingerprint   VARCHAR(64)  NOT NULL,
    task_version  BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_calendar_task_links_task UNIQUE (connection_id, task_id)
);

CREATE TABLE calendar_feeds (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    project_id UUID        REFERENCES projects (id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    token_enc  TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_calendar_feeds_token UNIQUE (token_hash)
);
-- một feed tài khoản / user và một feed / (user, dự án)
CREATE UNIQUE INDEX uq_calendar_feeds_account ON calendar_feeds (user_id) WHERE project_id IS NULL;
CREATE UNIQUE INDEX uq_calendar_feeds_project ON calendar_feeds (user_id, project_id) WHERE project_id IS NOT NULL;
CREATE INDEX ix_calendar_feeds_project ON calendar_feeds (project_id);
