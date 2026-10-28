-- DND (kéo-thả trong danh sách): thứ tự trong NGÀY của Today / Upcoming khi sort = date (day order kiểu Todoist).
-- day_order chỉ có hiệu lực khi day_order_day = due_day: đổi due sang ngày khác là tự vô hiệu, không cần sửa mọi đường
-- ghi due. NULL = chưa xếp → đứng sau các task đã xếp, theo khoá cũ (giờ → ưu tiên → dự án → sort_order).
ALTER TABLE tasks
    ADD COLUMN day_order     BIGINT,
    ADD COLUMN day_order_day DATE;
