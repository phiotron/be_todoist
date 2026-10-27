package com.taskly.dto.filter;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Các danh sách (tách theo ',' cấp ngoài cùng, đã cắt khoảng trắng) — app gọi tasks?filter=<part> cho từng phần. */
public record ValidateFilterResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> parts) {
}
