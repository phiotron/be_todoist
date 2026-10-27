-- V3 — Mốc M2: tìm kiếm (unaccent + pg_trgm), label, filter, Display (view_options).
-- Truy vấn phải viết ĐÚNG biểu thức f_unaccent(lower(cột)) thì planner mới dùng được chỉ mục trigram.
-- Hai extension có sẵn trong image postgres:16 và là "trusted" từ PG13; Postgres managed khi phát hành phải hỗ trợ.

CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA public;
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

-- unaccent() chỉ là STABLE (tra từ điển theo search_path) nên không dùng trong chỉ mục được.
-- Bọc lại với schema + từ điển ghi rõ → kết quả cố định → IMMUTABLE. Thân hàm kiểu SQL-standard (RETURN ...)
-- được phân tích ngay lúc tạo, không phụ thuộc search_path lúc chạy. unaccent.rules có sẵn đ→d, Đ→D.
CREATE OR REPLACE FUNCTION public.f_unaccent(text) RETURNS text
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN public.unaccent('public.unaccent'::regdictionary, $1);

CREATE INDEX ix_tasks_content_trgm ON tasks USING gin (public.f_unaccent(lower(content)) gin_trgm_ops);
CREATE INDEX ix_tasks_description_trgm ON tasks USING gin (public.f_unaccent(lower(description)) gin_trgm_ops);
CREATE INDEX ix_projects_name_trgm ON projects USING gin (public.f_unaccent(lower(name)) gin_trgm_ops);
CREATE INDEX ix_sections_name_trgm ON sections USING gin (public.f_unaccent(lower(name)) gin_trgm_ops);

-- Label cá nhân (spec 5.9): 1–60 ký tự, không khoảng trắng, không @ % # / , ( ) | & ! \ *; không trùng (không phân
-- biệt hoa thường) trong tài khoản. Màu là khoá chữ thường (enum ProjectColor), không CHECK ở DB (giống projects).
CREATE TABLE labels (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(60)  NOT NULL,
    color       VARCHAR(20)  NOT NULL DEFAULT 'charcoal',
    sort_order  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_labels_name CHECK (char_length(name) BETWEEN 1 AND 60
        AND name !~ '[[:space:]@%#/,()|&!\\*]')
);
CREATE UNIQUE INDEX ux_labels_user_lower_name ON labels (user_id, lower(name));
CREATE INDEX ix_labels_user_sort ON labels (user_id, sort_order);
CREATE INDEX ix_labels_name_trgm ON labels USING gin (public.f_unaccent(lower(name)) gin_trgm_ops);

CREATE TABLE task_labels (
    task_id   UUID NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    label_id  UUID NOT NULL REFERENCES labels (id) ON DELETE CASCADE,
    PRIMARY KEY (task_id, label_id)
);
CREATE INDEX ix_task_labels_label ON task_labels (label_id);

CREATE TABLE filters (
    id          UUID           PRIMARY KEY,
    user_id     UUID           NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(120)   NOT NULL,
    query       VARCHAR(1024)  NOT NULL,
    color       VARCHAR(20)    NOT NULL DEFAULT 'charcoal',
    sort_order  BIGINT         NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL,
    CONSTRAINT ck_filters_name CHECK (char_length(btrim(name)) BETWEEN 1 AND 120),
    CONSTRAINT ck_filters_query CHECK (char_length(btrim(query)) BETWEEN 1 AND 1024)
);
CREATE INDEX ix_filters_user_sort ON filters (user_id, sort_order);
CREATE INDEX ix_filters_name_trgm ON filters USING gin (public.f_unaccent(lower(name)) gin_trgm_ops);

-- Display đã lưu theo tài khoản + view (spec 10, 12.7). M7 thêm cột layout.
CREATE TABLE view_options (
    id               UUID         PRIMARY KEY,
    user_id          UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    view_key         VARCHAR(64)  NOT NULL,
    sort_by          VARCHAR(16)  NOT NULL,
    sort_direction   VARCHAR(4)   NOT NULL,
    group_by         VARCHAR(16)  NOT NULL,
    filter_priority  INTEGER      NULL,
    filter_label_id  UUID         NULL REFERENCES labels (id) ON DELETE SET NULL,
    filter_due       VARCHAR(16)  NULL,
    show_completed   BOOLEAN      NOT NULL DEFAULT false,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ux_view_options_user_key UNIQUE (user_id, view_key),
    CONSTRAINT ck_view_options_key CHECK (view_key IN ('TODAY', 'UPCOMING')
        OR view_key ~ '^(project|label|filter):[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    CONSTRAINT ck_view_options_sort_by CHECK (sort_by IN ('MANUAL', 'DATE', 'DEADLINE', 'PRIORITY', 'NAME', 'ADDED')),
    CONSTRAINT ck_view_options_direction CHECK (sort_direction IN ('ASC', 'DESC')),
    CONSTRAINT ck_view_options_group_by CHECK (group_by IN ('NONE', 'DATE', 'DEADLINE', 'PRIORITY', 'PROJECT', 'LABEL')),
    CONSTRAINT ck_view_options_priority CHECK (filter_priority BETWEEN 1 AND 4),
    CONSTRAINT ck_view_options_due CHECK (filter_due IN
        ('TODAY', 'THIS_WEEK', 'NEXT_7_DAYS', 'THIS_MONTH', 'NEXT_30_DAYS', 'NO_DATE', 'OVERDUE'))
);

-- Xoá dự án / label / filter → xoá Display của view đó (spec 5.7, 8.2 quy tắc 8): do SERVICE lo, KHÔNG có trigger.
-- ViewOptionsService nghe ProjectsDeletedEvent (M1, gồm dự án con bị cascade), LabelDeletedEvent, FilterDeletedEvent —
-- cùng transaction với lệnh xoá. Chỉ filter_label_id có khoá ngoại ON DELETE SET NULL ở trên.
