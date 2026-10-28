package com.taskly.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Đồng bộ "Sync tasks to calendar" định kỳ (M13b; mặc định 5 phút — taskly.calendar.sync-interval). Chỉ chạy khi
 * app.jobs.enabled (SchedulingConfig) — test tắt và gọi thẳng CalendarSyncService.
 */
@Component
public class CalendarSyncJob {

    private final CalendarSyncService syncService;

    public CalendarSyncJob(CalendarSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(fixedDelayString = "${taskly.calendar.sync-interval:PT5M}",
            initialDelayString = "${taskly.calendar.sync-interval:PT5M}")
    public void run() {
        syncService.syncAll();
    }
}
