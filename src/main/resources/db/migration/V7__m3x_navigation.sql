-- V7 — M3x, M3 bổ sung (điều hướng) theo spec bản 3.3 mục 3.1, 12.1.1, 18.2 N14; contract M6–M14 mục 2.
-- Dùng lại số V7 để trống sau khi bỏ M6a (contract mục 1). Dòng có sẵn nhận giá trị mặc định.

ALTER TABLE users
    -- Vị trí nút + (Todoist Settings → Navigation bar).
    ADD COLUMN add_button_placement     VARCHAR(6) NOT NULL DEFAULT 'RIGHT',
    -- Sync home view (Todoist Settings → General): false = mỗi máy giữ trang mở đầu riêng (lưu trên máy).
    ADD COLUMN sync_home_view           BOOLEAN    NOT NULL DEFAULT TRUE,
    -- Navigation menu (nội dung lối tắt của màn Browse, không phải drawer): Show task count + mục menu.
    ADD COLUMN nav_menu_show_task_count BOOLEAN    NOT NULL DEFAULT TRUE,
    -- Mục đang hiện, theo thứ tự, nối bằng ","; vắng = ẩn; chuỗi rỗng = ẩn hết. Không trùng: ứng dụng kiểm (@NavMenuItems).
    ADD COLUMN nav_menu_items           TEXT       NOT NULL
        DEFAULT 'INBOX,TODAY,UPCOMING,FILTERS_LABELS,REPORTING,BROWSE_TEMPLATES';

ALTER TABLE users
    ADD CONSTRAINT ck_users_add_button_placement CHECK (add_button_placement IN ('LEFT', 'CENTER', 'RIGHT')),
    ADD CONSTRAINT ck_users_nav_menu_items CHECK (nav_menu_items ~
        '^((INBOX|TODAY|UPCOMING|FILTERS_LABELS|REPORTING|BROWSE_TEMPLATES)(,(INBOX|TODAY|UPCOMING|FILTERS_LABELS|REPORTING|BROWSE_TEMPLATES)){0,5})?$');
