-- V2 — Mốc M1 (lõi task), spec bản 3.1 (mục 10, 13; review 2026-10-26: I5, I8, I10, m9). Quy ước như V1: id và
-- created_at/updated_at do ứng dụng ghi. `id` của projects / sections / tasks có thể do CLIENT sinh (spec 12.11) nên
-- KHÔNG có DEFAULT / sequence. Xoá là XOÁ VĨNH VIỄN (không thùng rác): phần đệ quy do ON DELETE CASCADE; favorites
-- (đa hình, không FK) do service xoá tay trong cùng transaction. Không có deleted_at / delete_batch_id / icon / trash_batches.

-- Họ refresh token: login/register mở họ mới, refresh kế thừa; logout thu hồi cả họ. Thêm cột NULLABLE → backfill
-- family_id = id cho token đang có (mỗi token cũ là một họ riêng) → mới đặt NOT NULL (spec 10, I8).
ALTER TABLE refresh_tokens ADD COLUMN family_id UUID;
UPDATE refresh_tokens SET family_id = id;
ALTER TABLE refresh_tokens ALTER COLUMN family_id SET NOT NULL;
-- DEFAULT: mã M0 chưa biết family_id (cho tới Task 9) vẫn chèn được token; Task 9 luôn gán family_id tường minh.
ALTER TABLE refresh_tokens ALTER COLUMN family_id SET DEFAULT gen_random_uuid();
CREATE INDEX ix_refresh_tokens_family_id ON refresh_tokens (family_id);

-- Dự án: dự án con (≤ 3 cấp — kiểm ở ProjectTreeService), mô tả, tên ≤ 120, sort_order BIGINT (bước 1024).
ALTER TABLE projects ALTER COLUMN name TYPE VARCHAR(120);
ALTER TABLE projects ALTER COLUMN sort_order TYPE BIGINT USING sort_order::bigint;
ALTER TABLE projects ADD COLUMN parent_id UUID REFERENCES projects (id) ON DELETE CASCADE;
ALTER TABLE projects ADD COLUMN description TEXT NOT NULL DEFAULT '';
ALTER TABLE projects ADD CONSTRAINT ck_projects_description_length CHECK (char_length(description) <= 16383);
ALTER TABLE projects ADD CONSTRAINT ck_projects_color_unless_inbox CHECK (is_inbox OR color IS NOT NULL);
-- Dự án M0 có thể mang màu cũ ngoài 20 khoá (spec 3.1): đưa về mặc định charcoal TRƯỚC khi thêm CHECK.
UPDATE projects SET color = 'charcoal' WHERE color IS NOT NULL AND color NOT IN (
    'berry_red', 'red', 'orange', 'yellow', 'olive_green', 'lime_green', 'green', 'mint_green', 'teal', 'sky_blue',
    'light_blue', 'blue', 'grape', 'violet', 'lavender', 'magenta', 'salmon', 'charcoal', 'grey', 'taupe');
-- 20 khoá màu (spec 10.1) — trùng enum ProjectColor; null chỉ cho Inbox (CHECK ở trên).
ALTER TABLE projects ADD CONSTRAINT ck_projects_color_values CHECK (color IS NULL OR color IN (
    'berry_red', 'red', 'orange', 'yellow', 'olive_green', 'lime_green', 'green', 'mint_green', 'teal', 'sky_blue',
    'light_blue', 'blue', 'grape', 'violet', 'lavender', 'magenta', 'salmon', 'charcoal', 'grey', 'taupe'));
-- View style: V1 chỉ cho LIST / BOARD; spec 3.1 thêm CALENDAR (layout chung ở M8, view_options.layout ở M7).
ALTER TABLE projects DROP CONSTRAINT ck_projects_view_style;
ALTER TABLE projects ADD CONSTRAINT ck_projects_view_style CHECK (view_style IN ('LIST', 'BOARD', 'CALENDAR'));
ALTER TABLE projects ADD CONSTRAINT ck_projects_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id);
ALTER TABLE projects ADD CONSTRAINT ck_projects_inbox_is_root CHECK (NOT is_inbox OR parent_id IS NULL);
-- Vùng chứa sắp xếp = (workspace, dự án cha) — spec 5.10.
CREATE INDEX ix_projects_workspace_parent_sort ON projects (workspace_id, parent_id, sort_order);
CREATE INDEX ix_projects_parent_id ON projects (parent_id);

CREATE TABLE sections (
    id          UUID          PRIMARY KEY,
    project_id  UUID          NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    name        VARCHAR(2048) NOT NULL,
    description TEXT          NOT NULL DEFAULT '',
    sort_order  BIGINT        NOT NULL,
    archived_at TIMESTAMPTZ,                       -- section lưu trữ: chỉ đọc với task của nó (spec 5.7)
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT ck_sections_description_length CHECK (char_length(description) <= 16383),
    -- đích của FK ghép từ tasks: bảo đảm section thuộc đúng dự án của task (spec 10, m9)
    CONSTRAINT uq_sections_project_id_id UNIQUE (project_id, id)
);
CREATE INDEX ix_sections_project_sort ON sections (project_id, sort_order);

CREATE TABLE tasks (
    id                  UUID         PRIMARY KEY,
    workspace_id        UUID         NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,  -- phi chuẩn = workspace của dự án (spec 10, I5)
    project_id          UUID         NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
    section_id          UUID,                          -- null = không section; FK ghép bên dưới
    parent_id           UUID         REFERENCES tasks (id) ON DELETE CASCADE,
    creator_id          UUID         REFERENCES users (id) ON DELETE SET NULL,
    content             VARCHAR(500) NOT NULL,
    description         TEXT         NOT NULL DEFAULT '',
    priority            INTEGER      NOT NULL DEFAULT 4,
    due_date            DATE,
    due_datetime        TIMESTAMP,                     -- giờ FLOATING: giờ địa phương, không múi giờ (spec 5.2)
    due_day             DATE GENERATED ALWAYS AS (COALESCE(due_date, CAST(due_datetime AS DATE))) STORED,
    due_sort            TIMESTAMP GENERATED ALWAYS AS (COALESCE(due_datetime, CAST(due_date AS TIMESTAMP))) STORED,  -- khoá sắp theo due (spec 10)
    due_string          VARCHAR(200),                  -- M6 (Quick Add); M1 luôn null
    recurrence_rule     VARCHAR(500),                  -- M6 (RRULE); M1 luôn null
    duration_minutes    INTEGER,
    deadline_date       DATE,
    sort_order          BIGINT       NOT NULL,
    completed_at        TIMESTAMPTZ,
    completion_batch_id UUID,                          -- cùng giá trị cho các task hoàn thành trong MỘT lần gọi — căn cứ Undo (spec 5.1, I10)
    version             BIGINT       NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_tasks_project_section FOREIGN KEY (project_id, section_id)
        REFERENCES sections (project_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_tasks_priority CHECK (priority BETWEEN 1 AND 4),
    CONSTRAINT ck_tasks_single_due CHECK (due_date IS NULL OR due_datetime IS NULL),
    CONSTRAINT ck_tasks_duration CHECK (duration_minutes IS NULL
        OR (duration_minutes BETWEEN 1 AND 1440 AND due_datetime IS NOT NULL)),
    CONSTRAINT ck_tasks_description_length CHECK (char_length(description) <= 16383),
    CONSTRAINT ck_tasks_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id)
);
-- Chỉ mục theo spec mục 5.10: vùng chứa sắp xếp (tiền tố (project_id, section_id) cũng phục vụ FK ghép khi xoá section),
-- Today / Upcoming / Overdue (một phần: chưa xong), màn Completed toàn cục (một phần: đã xong), Completed của dự án.
CREATE INDEX ix_tasks_container ON tasks (project_id, section_id, parent_id, sort_order);
CREATE INDEX ix_tasks_parent_id ON tasks (parent_id);
CREATE INDEX ix_tasks_creator_id ON tasks (creator_id);
CREATE INDEX ix_tasks_workspace_due ON tasks (workspace_id, due_day, due_sort, priority) WHERE completed_at IS NULL;
CREATE INDEX ix_tasks_workspace_completed ON tasks (workspace_id, completed_at DESC, id) WHERE completed_at IS NOT NULL;
CREATE INDEX ix_tasks_project_completed ON tasks (project_id, completed_at DESC);
CREATE INDEX ix_tasks_deadline_date ON tasks (deadline_date);

-- Yêu thích (đa hình, không FK tới đích): dự án (M1), label / filter (M2). Task KHÔNG có yêu thích (spec bản 3).
CREATE TABLE favorites (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    target_type VARCHAR(10) NOT NULL,
    target_id   UUID        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_favorites_user_target UNIQUE (user_id, target_type, target_id),
    CONSTRAINT ck_favorites_target_type CHECK (target_type IN ('PROJECT', 'LABEL', 'FILTER'))
);
CREATE INDEX ix_favorites_target ON favorites (target_type, target_id);

-- Chống gửi trùng (spec 12.11): hàng được chèn TRƯỚC khi xử lý (unique bắt đua); status/response_body null = đang xử lý,
-- expires_at = now + 60 giây; xong 2xx → expires_at = now + 24 giờ; lỗi → xoá hàng (IdempotencyService, Part 2 Task 16).
CREATE TABLE idempotency_keys (
    id            UUID         PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    idem_key      VARCHAR(64)  NOT NULL,
    method        VARCHAR(10)  NOT NULL,
    path          VARCHAR(300) NOT NULL,
    request_hash  VARCHAR(64)  NOT NULL,
    status        INTEGER,
    response_body TEXT,
    expires_at    TIMESTAMPTZ  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_idempotency_keys UNIQUE (user_id, idem_key, method, path)
);
CREATE INDEX ix_idempotency_keys_expires_at ON idempotency_keys (expires_at);
