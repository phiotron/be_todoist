package com.taskly.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dọn khoá chống gửi trùng đã hết hạn mỗi giờ (spec mục 17: bảng phình nếu không dọn; hạn 24 giờ — gồm cả hàng
 * "đang xử lý" bỏ dở mà chưa request nào chiếm lại). Tắt trong test (Q23).
 */
@Component
public class IdempotencyCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyCleanupJob.class);

    private final IdempotencyService idempotencyService;

    public IdempotencyCleanupJob(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void purgeExpired() {
        int deleted = idempotencyService.purgeExpired();
        log.info("Đã dọn {} Idempotency-Key hết hạn", deleted);
    }
}
