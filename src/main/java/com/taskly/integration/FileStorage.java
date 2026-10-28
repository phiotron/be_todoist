package com.taskly.integration;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Nơi lưu nội dung file (M8b: file đính kèm comment, ảnh đại diện). Bản chạy thật: LocalFileStorage (thư mục trên đĩa
 * server, taskly.storage.root). Khoá lưu do storage sinh ngẫu nhiên — KHÔNG dùng tên file người dùng gửi (chống ghi đè /
 * đi ngược thư mục). Đổi sang S3 sau này chỉ cần một bản cài khác của interface này.
 */
public interface FileStorage {

    /**
     * Ghi toàn bộ luồng vào một khoá mới, dừng và xoá phần đã ghi nếu vượt maxBytes. Trả khoá + số byte đã ghi.
     * Vượt maxBytes → TooLargeException (không để lại file). Lỗi đọc / ghi → IOException.
     */
    Stored store(InputStream content, long maxBytes) throws IOException;

    /** Mở để đọc; khoá không tồn tại (đã dọn) → Optional.empty(). */
    Optional<InputStream> open(String storageKey) throws IOException;

    /** Xoá; khoá không tồn tại → không lỗi (dọn lặp lại an toàn). */
    void delete(String storageKey) throws IOException;

    boolean exists(String storageKey);

    record Stored(String storageKey, long sizeBytes) {
    }

    /** Luồng dài hơn maxBytes; actualBytes = số byte đã đọc (≥ maxBytes + 1). */
    final class TooLargeException extends IOException {

        private final long actualBytes;

        public TooLargeException(long actualBytes) {
            super("File vượt cỡ cho phép");
            this.actualBytes = actualBytes;
        }

        public long actualBytes() {
            return actualBytes;
        }
    }
}
