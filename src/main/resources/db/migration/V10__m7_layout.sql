-- V10 — Mốc M7: Layout List / Board / Calendar theo người + view, Grouping "Added date", Future occurrences.
-- Hàng có sẵn nhận layout = LIST, show_future_occurrences = false (giá trị mặc định).

ALTER TABLE view_options
    ADD COLUMN layout VARCHAR(16) NOT NULL DEFAULT 'LIST',
    ADD COLUMN show_future_occurrences BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE view_options
    ADD CONSTRAINT ck_view_options_layout CHECK (layout IN ('LIST', 'BOARD', 'CALENDAR'));

-- Grouping "Added date" (ngày tạo theo múi giờ tài khoản).
ALTER TABLE view_options DROP CONSTRAINT ck_view_options_group_by;
ALTER TABLE view_options
    ADD CONSTRAINT ck_view_options_group_by
        CHECK (group_by IN ('NONE', 'DATE', 'DEADLINE', 'PRIORITY', 'PROJECT', 'LABEL', 'ADDED'));
