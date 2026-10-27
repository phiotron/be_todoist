package com.taskly.dto.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Kết quả của POST tạo bản ghi có thể nhận `id` do client sinh (spec 12.11): fresh = vừa tạo → 201; replay = `id` đã có,
 * cùng người dùng + cùng nội dung → trả bản ghi cũ, 200 + header `Idempotent-Replay: true`, KHÔNG ghi gì và KHÔNG đếm lại
 * giới hạn. Service trả Created<T>, controller gọi toResponseEntity().
 */
public record Created<T>(T body, boolean replayed) {

    public static <T> Created<T> fresh(T body) {
        return new Created<>(body, false);
    }

    public static <T> Created<T> replay(T body) {
        return new Created<>(body, true);
    }

    public ResponseEntity<T> toResponseEntity() {
        if (replayed) {
            return ResponseEntity.ok().header("Idempotent-Replay", "true").body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
