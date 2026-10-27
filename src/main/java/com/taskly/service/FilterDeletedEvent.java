package com.taskly.service;

import java.util.UUID;

/** Phát (đồng bộ, cùng transaction) khi xoá filter — ViewOptionsService xoá Display `filter:<id>` (spec 8.2 quy tắc 8). */
public record FilterDeletedEvent(UUID userId, UUID filterId) {
}
