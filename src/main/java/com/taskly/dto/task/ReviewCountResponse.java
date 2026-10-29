package com.taskly.dto.task;

import io.swagger.v3.oas.annotations.media.Schema;

/** Số việc chờ duyệt (badge). */
public record ReviewCountResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) long count) {
}
