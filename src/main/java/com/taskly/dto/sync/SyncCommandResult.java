package com.taskly.dto.sync;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;
import java.util.UUID;

/**
 * Kết quả một lệnh. status "ok" | "error". error = ProblemDetail phẳng (type, title, status, detail, code, errors…) đã
 * dịch theo Accept-Language — cùng hình dạng lỗi của API REST tương ứng; null khi ok.
 */
public record SyncCommandResult(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID uuid,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"ok", "error"}) String status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) Map<String, Object> error) {

    public static final String OK = "ok";
    public static final String ERROR = "error";

    public static SyncCommandResult ok(UUID uuid) {
        return new SyncCommandResult(uuid, OK, null);
    }

    public static SyncCommandResult error(UUID uuid, Map<String, Object> problem) {
        return new SyncCommandResult(uuid, ERROR, problem);
    }
}
