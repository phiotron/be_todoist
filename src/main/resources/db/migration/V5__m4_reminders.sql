-- V5 — M4 nhắc việc (spec 5.2, 5.8, 10, 10.1, 12.1.1, 12.8).

-- Cài đặt nhắc theo tài khoản. Hàng có sẵn nhận mặc định: nhắc bật, auto reminder 30 phút trước giờ.
-- auto_reminder_minutes NULL = tắt auto reminder (CHECK với NULL cho qua).
ALTER TABLE users
    ADD COLUMN reminders_enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN auto_reminder_minutes INTEGER DEFAULT 30,
    ADD CONSTRAINT ck_users_auto_reminder_minutes
        CHECK (auto_reminder_minutes IN (0, 5, 10, 15, 30, 45, 60, 120, 180, 1440));

-- Nhắc riêng của task. ABSOLUTE: at (giờ floating, không múi giờ, như tasks.due_datetime).
-- RELATIVE: minute_offset phút trước tasks.due_datetime. user_id = người đặt nhắc (giới hạn 700 / user).
CREATE TABLE reminders (
    id            UUID        PRIMARY KEY,
    task_id       UUID        NOT NULL REFERENCES tasks (id) ON DELETE CASCADE,
    user_id       UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type          VARCHAR(16) NOT NULL,
    at            TIMESTAMP,
    minute_offset INTEGER,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_reminders_type CHECK (type IN ('ABSOLUTE', 'RELATIVE')),
    CONSTRAINT ck_reminders_fields CHECK (
        (type = 'ABSOLUTE' AND at IS NOT NULL AND minute_offset IS NULL)
        OR (type = 'RELATIVE' AND at IS NULL AND minute_offset IS NOT NULL AND minute_offset BETWEEN 0 AND 10080))
);

CREATE INDEX ix_reminders_task_id ON reminders (task_id);
CREATE INDEX ix_reminders_user_id ON reminders (user_id);

-- Task mất giờ -> xoá nhắc tương đối: KHÔNG làm ở DB. ReminderService (@EventListener TaskTimeClearedEvent của M1)
-- xoá trong cùng transaction với lần ghi due (Quyết định #2).
