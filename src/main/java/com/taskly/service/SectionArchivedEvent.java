package com.taskly.service;

import java.time.Instant;
import java.util.UUID;

/**
 * Section vừa được lưu trữ — phát ĐỒNG BỘ trong transaction của SectionService.archive. TaskService nghe để hoàn
 * thành mọi task đang mở của section với completed_at = archivedAt và MỘT completion_batch_id mới do listener tự
 * sinh (spec 5.1, 5.7, 10.2). Dùng sự kiện vì SectionService không được gọi TaskService (TaskService đã phụ thuộc
 * SectionService — tránh vòng phụ thuộc).
 */
public record SectionArchivedEvent(UUID sectionId, Instant archivedAt) {
}
