package com.taskly.service;

import java.util.UUID;

/** Phát (đồng bộ, cùng transaction) khi xoá label — ViewOptionsService xoá Display `label:<id>` (spec 8.2 quy tắc 8). */
public record LabelDeletedEvent(UUID userId, UUID labelId) {
}
