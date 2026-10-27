-- V4 — Mốc M3 (tài khoản & cài đặt) theo spec bản 3 (mục 10, 12.1.1, 5.9). Viết lại tại chỗ ở Task 12f (V4 chưa phát
-- hành; spec 13: một file migration mỗi mốc). V2 (M1) và V3 (M2) dành cho mốc khác: Flyway chạy được V4 trên DB mới dù
-- chưa có V2/V3, nhưng DB đã ở V4 thì không nhận thêm V2/V3 về sau (xem "Rủi ro" trong plan M3).

-- Cài đặt theo tài khoản. Dòng cũ nhận giá trị mặc định của spec mục 10.
ALTER TABLE users
    ADD COLUMN theme                  VARCHAR(16) NOT NULL DEFAULT 'TASKLY',
    ADD COLUMN appearance             VARCHAR(8)  NOT NULL DEFAULT 'AUTO',
    ADD COLUMN start_day              INTEGER     NOT NULL DEFAULT 1,
    ADD COLUMN next_week              INTEGER     NOT NULL DEFAULT 1,
    ADD COLUMN weekend_start_day      INTEGER     NOT NULL DEFAULT 6,
    ADD COLUMN date_format            VARCHAR(3)  NOT NULL DEFAULT 'DMY',
    ADD COLUMN time_format            VARCHAR(3)  NOT NULL DEFAULT 'H24',
    ADD COLUMN start_page             VARCHAR(64) NOT NULL DEFAULT 'TODAY',
    ADD COLUMN smart_date_recognition BOOLEAN     NOT NULL DEFAULT TRUE,
    ADD COLUMN swipe_left             VARCHAR(10) NOT NULL DEFAULT 'SCHEDULE',
    ADD COLUMN swipe_right            VARCHAR(10) NOT NULL DEFAULT 'COMPLETE',
    ADD COLUMN nav_tabs               TEXT        NOT NULL DEFAULT 'INBOX,TODAY,UPCOMING,BROWSE';

ALTER TABLE users
    ADD CONSTRAINT ck_users_theme CHECK (theme IN
        ('TASKLY', 'MOONSTONE', 'TANGERINE', 'KALE', 'BLUEBERRY', 'LAVENDER', 'RASPBERRY')),
    ADD CONSTRAINT ck_users_appearance CHECK (appearance IN ('AUTO', 'LIGHT', 'DARK')),
    -- 1 = Thứ Hai … 7 = Chủ nhật (ISO-8601)
    ADD CONSTRAINT ck_users_start_day CHECK (start_day BETWEEN 1 AND 7),
    ADD CONSTRAINT ck_users_next_week CHECK (next_week BETWEEN 1 AND 7),
    ADD CONSTRAINT ck_users_weekend_start_day CHECK (weekend_start_day BETWEEN 1 AND 7),
    ADD CONSTRAINT ck_users_date_format CHECK (date_format IN ('DMY', 'MDY')),
    ADD CONSTRAINT ck_users_time_format CHECK (time_format IN ('H24', 'H12')),
    ADD CONSTRAINT ck_users_start_page CHECK (start_page ~
        '^(INBOX|TODAY|UPCOMING|(project|label|filter):[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$'),
    ADD CONSTRAINT ck_users_swipe_left CHECK (swipe_left IN
        ('COMPLETE', 'SCHEDULE', 'DELETE', 'REMINDERS', 'SELECT', 'NONE')),
    ADD CONSTRAINT ck_users_swipe_right CHECK (swipe_right IN
        ('COMPLETE', 'SCHEDULE', 'DELETE', 'REMINDERS', 'SELECT', 'NONE')),
    -- 3–5 khoá nối bằng ","; luật chi tiết (BROWSE cuối, không trùng, ≤ 1 mục ghim) do ứng dụng kiểm (@NavTabs).
    ADD CONSTRAINT ck_users_nav_tabs CHECK (nav_tabs ~
        '^([A-Z_]+|(project|label|filter):[0-9a-f-]{36})(,([A-Z_]+|(project|label|filter):[0-9a-f-]{36})){2,4}$');

-- Ngôn ngữ chỉ vi | en (cột locale có từ V1, mặc định 'vi').
ALTER TABLE users ADD CONSTRAINT ck_users_locale CHECK (locale IN ('vi', 'en'));

-- Token đặt lại mật khẩu: dùng một lần, hết hạn 30 phút, chỉ lưu SHA-256 hex (token thô chỉ nằm trong email).
-- Vô hiệu token cũ = đặt expires_at = thời điểm vô hiệu (không cần cột riêng).
CREATE TABLE password_reset_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_password_reset_tokens_token_hash UNIQUE (token_hash)
);
CREATE INDEX ix_password_reset_tokens_user_id ON password_reset_tokens (user_id);

-- 2FA TOTP (spec 5.9): mỗi user tối đa một secret. secret_encrypted = base64(IV ‖ AES-GCM(secret)) — KHÔNG lưu secret
-- thô. enabled_at null = đã setup, chưa bật. last_used_step = bước 30 giây đã dùng gần nhất (chống dùng lại mã).
CREATE TABLE user_totp (
    user_id          UUID         PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    secret_encrypted VARCHAR(255) NOT NULL,
    enabled_at       TIMESTAMPTZ,
    last_used_step   BIGINT,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

-- Mã khôi phục 2FA: 10 mã mỗi lần tạo, dùng một lần, chỉ lưu SHA-256 hex của dạng chuẩn hoá.
CREATE TABLE user_recovery_codes (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash  VARCHAR(64) NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_user_recovery_codes_user_code UNIQUE (user_id, code_hash)
);
