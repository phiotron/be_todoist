-- V4 — Mốc M3 (tài khoản & cài đặt). V2 (M1) và V3 (M2) dành cho mốc khác: Flyway chạy được V4 trên DB mới dù
-- chưa có V2/V3, nhưng DB đã ở V4 thì không nhận thêm V2/V3 về sau (xem "Rủi ro" trong plan M3).

-- Giao diện theo tài khoản: LIGHT | DARK | SYSTEM (theo sáng / tối của máy). Dòng cũ nhận mặc định SYSTEM.
ALTER TABLE users ADD COLUMN theme VARCHAR(10) NOT NULL DEFAULT 'SYSTEM';
ALTER TABLE users ADD CONSTRAINT ck_users_theme CHECK (theme IN ('LIGHT', 'DARK', 'SYSTEM'));

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
