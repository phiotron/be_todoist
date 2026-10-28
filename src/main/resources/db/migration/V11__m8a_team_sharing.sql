-- V11 — M8a Team & chia sẻ (spec 3.2 M8a, 11.2, 12.9; plan docs/superpowers/plans/2026-10-27-m8a-chuc-nang.md).

-- 1. Vai trò workspace: ADMIN | MEMBER | GUEST (VIEWER của V1 chưa từng dùng → GUEST).
UPDATE workspace_members SET role = 'GUEST' WHERE role = 'VIEWER';
ALTER TABLE workspace_members DROP CONSTRAINT ck_workspace_members_role;
ALTER TABLE workspace_members
    ADD CONSTRAINT ck_workspace_members_role CHECK (role IN ('ADMIN', 'MEMBER', 'GUEST'));

-- 2. Link mời workspace (một link chung, dùng nhiều lần, lưu nguyên để chép lại; NULL = tắt — ADR 0003).
ALTER TABLE workspaces ADD COLUMN invite_link_token VARCHAR(64);
CREATE UNIQUE INDEX ux_workspaces_invite_link_token ON workspaces (invite_link_token)
    WHERE invite_link_token IS NOT NULL;

-- 3. Folder gom dự án của workspace nhóm (team không có dự án con).
CREATE TABLE folders (
    id           UUID         PRIMARY KEY,
    workspace_id UUID         NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    name         VARCHAR(120) NOT NULL,
    sort_order   BIGINT       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_folders_workspace ON folders (workspace_id, sort_order);

-- 4. Dự án: folder, quyền truy cập (chỉ dự án nhóm), người tạo, link mời.
ALTER TABLE projects
    ADD COLUMN folder_id         UUID REFERENCES folders (id) ON DELETE SET NULL,
    ADD COLUMN access            VARCHAR(10),
    ADD COLUMN created_by        UUID REFERENCES users (id) ON DELETE SET NULL,
    ADD COLUMN invite_link_token VARCHAR(64),
    ADD CONSTRAINT ck_projects_access CHECK (access IS NULL OR access IN ('TEAM', 'RESTRICTED'));
CREATE INDEX ix_projects_folder_id ON projects (folder_id);
CREATE UNIQUE INDEX ux_projects_invite_link_token ON projects (invite_link_token)
    WHERE invite_link_token IS NOT NULL;

-- 5. Thành viên dự án: người cộng tác của dự án cá nhân (chủ workspace cá nhân KHÔNG có hàng ở đây) và người đã tham
--    gia dự án nhóm (người tạo có hàng).
CREATE TABLE project_members (
    id         UUID        PRIMARY KEY,
    project_id UUID        NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_project_members_project_user UNIQUE (project_id, user_id)
);
CREATE INDEX ix_project_members_user ON project_members (user_id);

-- 6. Lời mời email (hạn 7 ngày, dùng một lần, trùng email, chỉ lưu SHA-256 của token — ADR 0003).
--    Đích là MỘT workspace HOẶC MỘT dự án. role: vai trò khi vào workspace (lời mời dự án luôn MEMBER, người ngoài team
--    vào dự án nhóm thành GUEST lúc chấp nhận).
CREATE TABLE invitations (
    id           UUID         PRIMARY KEY,
    workspace_id UUID         REFERENCES workspaces (id) ON DELETE CASCADE,
    project_id   UUID         REFERENCES projects (id) ON DELETE CASCADE,
    email        VARCHAR(254) NOT NULL,
    role         VARCHAR(20)  NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL,
    invited_by   UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at   TIMESTAMPTZ  NOT NULL,
    accepted_at  TIMESTAMPTZ,
    declined_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_invitations_target CHECK ((workspace_id IS NULL) <> (project_id IS NULL)),
    CONSTRAINT ck_invitations_role CHECK (role IN ('ADMIN', 'MEMBER', 'GUEST'))
);
CREATE UNIQUE INDEX ux_invitations_token_hash ON invitations (token_hash);
CREATE INDEX ix_invitations_email ON invitations (email);
CREATE INDEX ix_invitations_project ON invitations (project_id);
CREATE INDEX ix_invitations_workspace ON invitations (workspace_id);

-- 7. Cài đặt Auto accept invites (mặc định tắt — plan M8a câu hỏi 3, chờ duyệt).
ALTER TABLE users ADD COLUMN auto_accept_invites BOOLEAN NOT NULL DEFAULT FALSE;
