-- V15 — Mốc M11 (template & dữ liệu), spec bản 3.3 mục 2.1, 3.2, 13; plan 2026-10-27-m11-chuc-nang.md.
-- Quy ước như V1/V2: id và created_at / updated_at do ứng dụng ghi.

-- Move section (F3): section đổi dự án thì mọi task của nó (FK ghép (project_id, section_id) → sections(project_id, id))
-- đi theo. FK cũ không có ON UPDATE → đổi sections.project_id bị chặn khi section còn task. Thêm ON UPDATE CASCADE
-- (chỉ Move section đổi sections.project_id); ON DELETE CASCADE giữ nguyên.
ALTER TABLE tasks DROP CONSTRAINT fk_tasks_project_section;
ALTER TABLE tasks ADD CONSTRAINT fk_tasks_project_section FOREIGN KEY (project_id, section_id)
    REFERENCES sections (project_id, id) ON DELETE CASCADE ON UPDATE CASCADE;

-- Template của người dùng (F5): ảnh chụp section + task đang mở (JSON) lúc "Save as template". ≤ 100 / người
-- (LimitKind.TEMPLATES). Thư viện dựng sẵn KHÔNG nằm trong DB (resources/templates/library.json).
CREATE TABLE templates (
    id            UUID         PRIMARY KEY,
    user_id       UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name          VARCHAR(120) NOT NULL,
    description   TEXT         NOT NULL DEFAULT '',
    color         VARCHAR(20)  NOT NULL,
    content       TEXT         NOT NULL,
    section_count INTEGER      NOT NULL,
    task_count    INTEGER      NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_templates_name CHECK (char_length(btrim(name)) BETWEEN 1 AND 120),
    CONSTRAINT ck_templates_description_length CHECK (char_length(description) <= 16383)
);
CREATE INDEX ix_templates_user_created ON templates (user_id, created_at DESC);

-- Backup hằng ngày phía server (F8): JSON toàn bộ dự án / section / task / label của người dùng; giữ 21 bản mới nhất
-- (LimitKind.BACKUPS). Không có khôi phục / tải về trên mobile (ADR 0006).
CREATE TABLE backups (
    id            UUID        PRIMARY KEY,
    user_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    content       TEXT        NOT NULL,
    content_hash  VARCHAR(64) NOT NULL,
    size_bytes    BIGINT      NOT NULL,
    project_count INTEGER     NOT NULL,
    task_count    INTEGER     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX ix_backups_user_created ON backups (user_id, created_at DESC);

-- Lần chạy backup gần nhất của mỗi người dùng (kể cả lần không tạo bản mới vì không đổi gì): mốc "tới hạn" lúc nửa đêm
-- theo users.time_zone và hash để so "có thay đổi".
CREATE TABLE backup_runs (
    user_id     UUID        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    last_run_at TIMESTAMPTZ NOT NULL,
    last_hash   VARCHAR(64)
);
