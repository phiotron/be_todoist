package com.taskly.service.sync;

import com.taskly.repository.SyncChangeRepository;
import com.taskly.repository.SyncCommandRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dọn mỗi giờ (plan M9 F3): tombstone và uuid lệnh đã chạy cũ hơn taskly.sync.retention-days (30). Token cũ hơn hạn này
 * bị SyncService bắt full sync nên không sót lần xoá nào. Lịch chạy theo cùng công tắc job như IdempotencyCleanupJob.
 */
@Component
public class SyncCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(SyncCleanupJob.class);

    private final SyncChangeRepository changeRepository;
    private final SyncCommandRepository commandRepository;
    private final Clock clock;
    private final Duration retention;

    public SyncCleanupJob(SyncChangeRepository changeRepository, SyncCommandRepository commandRepository, Clock clock,
                          @Value("${taskly.sync.retention-days:30}") int retentionDays) {
        this.changeRepository = changeRepository;
        this.commandRepository = commandRepository;
        this.clock = clock;
        this.retention = Duration.ofDays(retentionDays);
    }

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT7M")
    public void purge() {
        Instant cutoff = clock.instant().minus(retention);
        int tombstones = changeRepository.purgeTombstonesBefore(cutoff);
        int commands = commandRepository.purgeOlderThan(cutoff);
        log.info("Đã dọn {} tombstone và {} lệnh đồng bộ cũ", tombstones, commands);
    }
}
