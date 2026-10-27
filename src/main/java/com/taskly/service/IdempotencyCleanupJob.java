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

    /**
     * Heartbeat: gia hạn khoá "đang xử lý" 60 giây của các request còn chạy (mặc định mỗi 15 giây = TTL / 4;
     * taskly.idempotency.renew-interval — chỉ test rút ngắn cùng taskly.idempotency.in-progress-ttl).
     */
    @Scheduled(fixedDelayString = "${taskly.idempotency.renew-interval:PT15S}",
            initialDelayString = "${taskly.idempotency.renew-interval:PT15S}")
    public void renewLeases() {
        idempotencyService.renewLeases();
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void purgeExpired() {
        int deleted = idempotencyService.purgeExpired();
        log.info("Đã dọn {} Idempotency-Key hết hạn", deleted);
    }
}
