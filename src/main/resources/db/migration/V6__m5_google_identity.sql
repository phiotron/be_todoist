-- V6 — Mốc M5 (đăng nhập Google). Số migration đã chốt: M1=V2, M2=V3, M3=V4, M4=V5, M5=V6.
-- 1) Tài khoản tạo bằng Google không có mật khẩu → password_hash null được.
-- 2) user_identities: liên kết đăng nhập ngoài. subject = claim "sub" của Google (ổn định, không tái sử dụng,
--    tối đa 255 ký tự ASCII). email = email Google lúc liên kết (chỉ để hiển thị / tra cứu, KHÔNG dùng làm định danh).

ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE user_identities (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider   VARCHAR(20)  NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    email      VARCHAR(254) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    -- một tài khoản Google chỉ gắn với một user
    CONSTRAINT uq_user_identities_provider_subject UNIQUE (provider, subject),
    -- một user chỉ gắn tối đa một tài khoản của mỗi nhà cung cấp (chỉ mục này cũng phục vụ tra theo user_id)
    CONSTRAINT uq_user_identities_user_provider UNIQUE (user_id, provider),
    CONSTRAINT ck_user_identities_provider CHECK (provider IN ('GOOGLE'))
);
