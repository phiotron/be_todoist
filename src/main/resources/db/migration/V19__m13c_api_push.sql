-- V19 — Mốc M13c (API công khai + OAuth cho app bên thứ ba, webhooks, push từ server, email nhắc).
-- Bí mật chỉ lưu dạng băm (client secret, mã ủy quyền, token OAuth, device token → SHA-256); secret ký webhook lưu mã hoá
-- AES-GCM (SecretCipher) vì phải dùng lại để ký.

-- Ứng dụng của nhà phát triển. client_id là công khai (chuẩn OAuth), client_secret chỉ lưu SHA-256.
CREATE TABLE api_clients (
    id                 UUID          PRIMARY KEY,
    owner_id           UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name               VARCHAR(100)  NOT NULL,
    client_id          VARCHAR(64)   NOT NULL,
    client_secret_hash VARCHAR(64)   NOT NULL,
    redirect_uri       VARCHAR(2048) NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_api_clients_client_id UNIQUE (client_id)
);
CREATE INDEX ix_api_clients_owner ON api_clients (owner_id);

-- Mã ủy quyền (authorization code + PKCE S256): dùng một lần, sống 10 phút.
CREATE TABLE oauth_authorization_codes (
    id             UUID          PRIMARY KEY,
    api_client_id  UUID          NOT NULL REFERENCES api_clients (id) ON DELETE CASCADE,
    user_id        UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    code_hash      VARCHAR(64)   NOT NULL,
    redirect_uri   VARCHAR(2048) NOT NULL,
    scopes         TEXT          NOT NULL,
    code_challenge VARCHAR(128)  NOT NULL,
    expires_at     TIMESTAMPTZ   NOT NULL,
    used_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_oauth_codes_hash UNIQUE (code_hash)
);
CREATE INDEX ix_oauth_codes_client ON oauth_authorization_codes (api_client_id);
CREATE INDEX ix_oauth_codes_user ON oauth_authorization_codes (user_id);

-- Token OAuth "mờ" (tiền tố tkl_): client giữ chuỗi thô, DB chỉ lưu SHA-256. Không hết hạn, thu hồi được ngay.
CREATE TABLE oauth_tokens (
    id            UUID         PRIMARY KEY,
    api_client_id UUID         NOT NULL REFERENCES api_clients (id) ON DELETE CASCADE,
    user_id       UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash    VARCHAR(64)  NOT NULL,
    scopes        TEXT         NOT NULL,
    code_id       UUID         REFERENCES oauth_authorization_codes (id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ,
    CONSTRAINT uq_oauth_tokens_hash UNIQUE (token_hash)
);
CREATE INDEX ix_oauth_tokens_user ON oauth_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_oauth_tokens_client ON oauth_tokens (api_client_id);
CREATE INDEX ix_oauth_tokens_code ON oauth_tokens (code_id);

-- Mỗi app một webhook. events = danh sách sự kiện cách nhau dấu phẩy.
CREATE TABLE webhooks (
    id            UUID          PRIMARY KEY,
    api_client_id UUID          NOT NULL REFERENCES api_clients (id) ON DELETE CASCADE,
    url           VARCHAR(2048) NOT NULL,
    events        TEXT          NOT NULL,
    secret_enc    TEXT          NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uq_webhooks_client UNIQUE (api_client_id)
);

-- Hàng đợi gửi webhook: tối đa 1 lần đầu + 3 lần thử lại (attempts đếm số request đã gửi).
CREATE TABLE webhook_deliveries (
    id               UUID         PRIMARY KEY,
    webhook_id       UUID         NOT NULL REFERENCES webhooks (id) ON DELETE CASCADE,
    user_id          UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    event_name       VARCHAR(40)  NOT NULL,
    payload          TEXT         NOT NULL,
    attempts         INTEGER      NOT NULL DEFAULT 0,
    status           VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    next_attempt_at  TIMESTAMPTZ  NOT NULL,
    last_status_code INTEGER,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_webhook_deliveries_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);
CREATE INDEX ix_webhook_deliveries_due ON webhook_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_webhook_deliveries_webhook ON webhook_deliveries (webhook_id);
CREATE INDEX ix_webhook_deliveries_user ON webhook_deliveries (user_id);

-- Device token FCM của app Android. token_hash (SHA-256) là khoá duy nhất vì token có thể dài tới 4096 ký tự.
CREATE TABLE push_tokens (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token      TEXT         NOT NULL,
    token_hash VARCHAR(64)  NOT NULL,
    platform   VARCHAR(10)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_push_tokens_hash UNIQUE (token_hash),
    CONSTRAINT ck_push_tokens_platform CHECK (platform IN ('ANDROID')),
    CONSTRAINT ck_push_tokens_length CHECK (char_length(token) BETWEEN 1 AND 4096)
);
CREATE INDEX ix_push_tokens_user ON push_tokens (user_id);

-- Cài đặt thông báo từ server. Chưa có hàng = mặc định (Subscribed emails bật, còn lại tắt).
CREATE TABLE notification_prefs (
    user_id           UUID        PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    reminder_push     BOOLEAN     NOT NULL DEFAULT FALSE,
    reminder_email    BOOLEAN     NOT NULL DEFAULT FALSE,
    morning_overview  BOOLEAN     NOT NULL DEFAULT FALSE,
    evening_review    BOOLEAN     NOT NULL DEFAULT FALSE,
    subscribed_emails BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL
);

-- Hàng đợi push / email. UNIQUE (user_id, channel, dedupe_key) = khoá chống gửi trùng (spec 5.8).
CREATE TABLE notification_deliveries (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    channel         VARCHAR(5)   NOT NULL,
    kind            VARCHAR(20)  NOT NULL,
    dedupe_key      VARCHAR(200) NOT NULL,
    title           TEXT         NOT NULL,
    body            TEXT         NOT NULL,
    data            TEXT,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    status          VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_notification_deliveries_dedupe UNIQUE (user_id, channel, dedupe_key),
    CONSTRAINT ck_notification_deliveries_channel CHECK (channel IN ('PUSH', 'EMAIL')),
    CONSTRAINT ck_notification_deliveries_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);
CREATE INDEX ix_notification_deliveries_due ON notification_deliveries (next_attempt_at) WHERE status = 'PENDING';
