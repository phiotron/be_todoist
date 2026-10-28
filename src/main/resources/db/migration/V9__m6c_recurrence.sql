-- V9 — M6c việc lặp (spec 3.2, 5.2, 5.3, 12.9; contract 8.1; plan 2026-10-27-m6c-chuc-nang.md).
-- tasks.recurrence_rule / due_string đã có từ V2. V2 KHÔNG có CHECK "task lặp không deadline" → không phải bỏ (bản 3.2
-- cho phép deadline với task lặp).

ALTER TABLE tasks
    -- `every!` = TRUE (kỳ kế tính từ ngày hoàn thành), `every` = FALSE (từ ngày lên lịch)
    ADD COLUMN recurrence_from_completion   BOOLEAN     NOT NULL DEFAULT FALSE,
    -- múi giờ cố định của due có giờ (IANA); NULL = floating (mặc định, spec 5.2)
    ADD COLUMN due_timezone                 VARCHAR(64),
    -- ảnh chụp Undo của lần hoàn thành task lặp gần nhất (spec 5.3 "Undo task lặp trả lại due cũ"):
    -- task lặp: batch + due cũ; con bị "reset sub-tasks": batch + completed_at cũ.
    ADD COLUMN recurrence_undo_batch_id     UUID,
    ADD COLUMN recurrence_undo_due_date     DATE,
    ADD COLUMN recurrence_undo_due_datetime TIMESTAMP,
    ADD COLUMN recurrence_undo_completed_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_tasks_due_timezone CHECK (due_timezone IS NULL OR due_datetime IS NOT NULL),
    ADD CONSTRAINT ck_tasks_recurrence_needs_due
        CHECK (recurrence_rule IS NULL OR due_date IS NOT NULL OR due_datetime IS NOT NULL);

-- Cài đặt "Reset sub-tasks" (Todoist Settings › General): tự mở lại task con khi hoàn thành task lặp.
ALTER TABLE users
    ADD COLUMN reset_subtasks BOOLEAN NOT NULL DEFAULT FALSE;
