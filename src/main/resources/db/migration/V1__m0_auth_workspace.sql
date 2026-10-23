-- V1 — Bảng của mốc M0: users, refresh_tokens, workspaces, workspace_members, projects.
-- id do ứng dụng sinh (UUID, Hibernate GenerationType.UUID); created_at/updated_at do ứng dụng ghi
-- (Spring Data JPA Auditing lấy giờ từ bean Clock), nên không có DEFAULT now() ở đây.

CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,          -- luôn lưu dạng đã chuẩn hoá (strip + chữ thường)
    password_hash VARCHAR(100) NOT NULL,          -- BCrypt
    display_name  VARCHAR(100) NOT NULL,
    locale        VARCHAR(10)  NOT NULL DEFAULT 'vi',
    time_zone     VARCHAR(64)  NOT NULL DEFAULT 'Asia/Ho_Chi_Minh',
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email)
);

CREATE TABLE refresh_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL,             -- SHA-256 hex; token thô không bao giờ lưu
    expires_at  TIMESTAMPTZ NOT NULL,
    revoked_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_refresh_tokens_user_id ON refresh_tokens (user_id);

CREATE TABLE workspaces (
    id         UUID         PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    type       VARCHAR(20)  NOT NULL,
    owner_id   UUID         NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_workspaces_type CHECK (type IN ('PERSONAL', 'TEAM'))
);
CREATE INDEX ix_workspaces_owner_id ON workspaces (owner_id);
-- Mỗi user có đúng một workspace cá nhân.
CREATE UNIQUE INDEX ux_workspaces_one_personal_per_owner ON workspaces (owner_id) WHERE type = 'PERSONAL';

CREATE TABLE workspace_members (
    id           UUID        PRIMARY KEY,
    workspace_id UUID        NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         VARCHAR(20) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_workspace_members_workspace_user UNIQUE (workspace_id, user_id),
    CONSTRAINT ck_workspace_members_role CHECK (role IN ('ADMIN', 'MEMBER', 'VIEWER'))
);
CREATE INDEX ix_workspace_members_user_id ON workspace_members (user_id);

CREATE TABLE projects (
    id           UUID         PRIMARY KEY,
    workspace_id UUID         NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    name         VARCHAR(100) NOT NULL,
    color        VARCHAR(20),                     -- bảng màu chốt ở M1; Inbox để null
    is_inbox     BOOLEAN      NOT NULL DEFAULT FALSE,
    view_style   VARCHAR(10)  NOT NULL DEFAULT 'LIST',
    sort_order   INTEGER      NOT NULL DEFAULT 0,
    archived_at  TIMESTAMPTZ,                     -- vòng 1 luôn null
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_projects_view_style CHECK (view_style IN ('LIST', 'BOARD'))
);
CREATE INDEX ix_projects_workspace_id ON projects (workspace_id);
-- Mỗi workspace có tối đa một Inbox.
CREATE UNIQUE INDEX ux_projects_one_inbox_per_workspace ON projects (workspace_id) WHERE is_inbox;
