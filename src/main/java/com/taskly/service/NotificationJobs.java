package com.taskly.service;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job nền M13c (tắt trong test cùng app.jobs.enabled=false; test gọi thẳng từng service): mỗi phút lập + gửi nhắc /
 * email tóm tắt, mỗi 10 giây gửi webhook tới hạn. Lỗi một vòng chỉ được ghi log, vòng sau chạy tiếp.
 */
@Component
public class NotificationJobs {

    private static final Logger log = LoggerFactory.getLogger(NotificationJobs.class);

    private final NotificationPlanner planner;
    private final NotificationDeliveryService delivery;
    private final WebhookDispatchService webhooks;
    private final Clock clock;

    public NotificationJobs(NotificationPlanner planner, NotificationDeliveryService delivery,
                            WebhookDispatchService webhooks, Clock clock) {
        this.planner = planner;
        this.delivery = delivery;
        this.webhooks = webhooks;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    public void notifications() {
        try {
            int reminders = planner.planReminders(clock.instant());
            int digests = planner.planDigests(clock.instant());
            int sent = delivery.deliverDue();
            if (reminders + digests + sent > 0) {
                log.info("Thông báo: xếp {} nhắc, {} email tóm tắt, xử lý {} hàng", reminders, digests, sent);
            }
        } catch (RuntimeException e) {
            log.warn("Vòng thông báo lỗi ({})", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "PT10S", initialDelayString = "PT20S")
    public void webhooks() {
        try {
            webhooks.dispatchDue();
        } catch (RuntimeException e) {
            log.warn("Vòng webhook lỗi ({})", e.getClass().getSimpleName());
        }
    }
}
