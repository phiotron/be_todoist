package com.taskly.service;

import java.util.List;
import java.util.UUID;

/**
 * Task mất giờ (đổi sang chỉ ngày / bỏ due) — phát ĐỒNG BỘ trong transaction. M1 không có listener; M4 nghe để xoá
 * nhắc RELATIVE của các task này (spec 5.8, 10.1).
 */
public record TaskTimeClearedEvent(List<UUID> taskIds) {
}
