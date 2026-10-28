package com.taskly.service;

import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * M11 F8: mỗi taskly.backups.check-interval (mặc định 15 phút) tạo backup cho người dùng vừa qua nửa đêm theo múi giờ
 * tài khoản (BackupService). Chỉ chạy khi SchedulingConfig bật (app.jobs.enabled — test tắt, gọi thẳng service).
 */
@Component
public class BackupJob {

    private final BackupService backupService;
    private final Clock clock;

    public BackupJob(BackupService backupService, Clock clock) {
        this.backupService = backupService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${taskly.backups.check-interval:PT15M}", initialDelayString = "PT2M")
    public void run() {
        backupService.runDue(clock.instant());
    }
}
