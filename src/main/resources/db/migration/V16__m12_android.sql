-- V16 — M12 Android mở rộng (spec 3.2 "M12", 13; contract mục 2; plan 2026-10-27-m12-chuc-nang.md).

-- Cài đặt nhắc theo tài khoản. snooze_minutes mặc định 15 (giữ như M4; Todoist 30 — chờ người dùng duyệt).
-- urgent_device_id: id cài đặt (UUID máy tự sinh) của thiết bị nhận urgent reminder; NULL = chưa chọn.
ALTER TABLE users
    ADD COLUMN snooze_minutes   INTEGER     NOT NULL DEFAULT 15,
    ADD COLUMN pin_reminders    BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN urgent_device_id VARCHAR(64),
    ADD CONSTRAINT ck_users_snooze_minutes CHECK (snooze_minutes IN (5, 10, 15, 30, 45, 60, 120, 180));

-- Địa điểm đã lưu cho nhắc theo vị trí (Settings → Reminders → Locations). Thuộc người tạo.
CREATE TABLE locations (
    id         UUID             PRIMARY KEY,
    user_id    UUID             NOT NULL,
    name       VARCHAR(100)     NOT NULL,
    latitude   DOUBLE PRECISION NOT NULL,
    longitude  DOUBLE PRECISION NOT NULL,
    radius_m   INTEGER          NOT NULL,
    created_at TIMESTAMPTZ      NOT NULL,
    updated_at TIMESTAMPTZ      NOT NULL,
    CONSTRAINT fk_locations_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_locations_name CHECK (length(btrim(name)) > 0),
    CONSTRAINT ck_locations_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_locations_longitude CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_locations_radius CHECK (radius_m BETWEEN 100 AND 5000)
);

CREATE INDEX ix_locations_user_id ON locations (user_id);

-- Nhắc: thêm loại LOCATION (location_id + location_trigger, không at / minute_offset) và cờ urgent (chỉ nhắc theo giờ).
-- Xoá địa điểm → xoá các nhắc vị trí dùng nó.
ALTER TABLE reminders
    ADD COLUMN location_id      UUID,
    ADD COLUMN location_trigger VARCHAR(10),
    ADD COLUMN urgent           BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT fk_reminders_location FOREIGN KEY (location_id) REFERENCES locations (id) ON DELETE CASCADE,
    DROP CONSTRAINT ck_reminders_type,
    DROP CONSTRAINT ck_reminders_fields;

ALTER TABLE reminders
    ADD CONSTRAINT ck_reminders_type CHECK (type IN ('ABSOLUTE', 'RELATIVE', 'LOCATION')),
    ADD CONSTRAINT ck_reminders_fields CHECK (
        (type = 'ABSOLUTE' AND at IS NOT NULL AND minute_offset IS NULL
            AND location_id IS NULL AND location_trigger IS NULL)
        OR (type = 'RELATIVE' AND at IS NULL AND minute_offset IS NOT NULL AND minute_offset BETWEEN 0 AND 10080
            AND location_id IS NULL AND location_trigger IS NULL)
        OR (type = 'LOCATION' AND at IS NULL AND minute_offset IS NULL
            AND location_id IS NOT NULL AND location_trigger IS NOT NULL
            AND location_trigger IN ('ON_ENTER', 'ON_LEAVE'))),
    ADD CONSTRAINT ck_reminders_urgent CHECK (NOT urgent OR type <> 'LOCATION');

CREATE INDEX ix_reminders_location_id ON reminders (location_id);
