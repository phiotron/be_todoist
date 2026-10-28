package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.integration.OutgoingMail;
import com.taskly.support.M13cApiTestBase;
import com.taskly.support.TestData.TaskRow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Email Morning overview / Evening review + nhắc việc qua push / email (plan M13c, F6-F7): giờ theo múi giờ tài khoản,
 * khoá chống gửi trùng, thử lại 3 lần. Giờ giả bằng clock.freezeAt; mặc định tài khoản ở Asia/Ho_Chi_Minh (UTC+7).
 */
class NotificationPlannerTest extends M13cApiTestBase {

    /** 2030-03-15 08:00 giờ Việt Nam. */
    private static final Instant MORNING = Instant.parse("2030-03-15T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2030, 3, 15);

    @Autowired
    private NotificationPlanner planner;
    @Autowired
    private NotificationDeliveryService delivery;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("delete from notification_deliveries");
        jdbcTemplate.update("delete from notification_prefs");
    }

    private RegisteredUser userWith(String prefsJson) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPatch(user.accessToken(), "/api/v1/me/notification-prefs", prefsJson);
        return user;
    }

    private void open(RegisteredUser user, String content, LocalDate due) {
        testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), content).dueDate(due));
    }

    private List<OutgoingMail> mails(RegisteredUser user) {
        return mailbox.sentTo(user.email());
    }

    // ---- Morning overview ----

    @Test
    void morning_listsTodayAndOverdue_notTomorrowOrDone() throws Exception {
        RegisteredUser user = userWith("{\"morningOverview\": true}");
        open(user, "Việc hôm nay A", TODAY);
        open(user, "Việc hôm nay B", TODAY);
        open(user, "Việc trễ hạn", TODAY.minusDays(2));
        open(user, "Việc ngày mai", TODAY.plusDays(1));
        testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), "Việc đã xong").dueDate(TODAY)
                .completedAt(MORNING.minusSeconds(3600)));
        clock.freezeAt(MORNING);

        assertThat(planner.planDigests(clock.instant())).isEqualTo(1);
        delivery.deliverDue();

        assertThat(mails(user)).hasSize(1);
        OutgoingMail mail = mails(user).get(0);
        assertThat(mail.subject()).isEqualTo("Taskly - Hôm nay: 3 việc");
        assertThat(mail.body()).contains("- Việc hôm nay A", "- Việc hôm nay B", "- Việc trễ hạn (quá hạn)")
                .doesNotContain("Việc ngày mai", "Việc đã xong");
    }

    @Test
    void morning_isOncePerLocalDay_andAgainTheNextDay() throws Exception {
        RegisteredUser user = userWith("{\"morningOverview\": true}");
        open(user, "Việc", TODAY);
        clock.freezeAt(MORNING);
        assertThat(planner.planDigests(clock.instant())).isEqualTo(1);
        assertThat(planner.planDigests(clock.instant())).isZero();
        clock.freezeAt(MORNING.plus(Duration.ofHours(2)));
        assertThat(planner.planDigests(clock.instant())).isZero();
        clock.freezeAt(MORNING.plus(Duration.ofDays(1)));
        assertThat(planner.planDigests(clock.instant())).isEqualTo(1);
    }

    @Test
    void morning_onlyInsideTheWindow() throws Exception {
        RegisteredUser user = userWith("{\"morningOverview\": true}");
        open(user, "Việc", TODAY);
        clock.freezeAt(Instant.parse("2030-03-14T23:59:00Z")); // 06:59 địa phương
        assertThat(planner.planDigests(clock.instant())).isZero();
        clock.freezeAt(Instant.parse("2030-03-15T04:00:00Z")); // 11:00 địa phương: hết khung
        assertThat(planner.planDigests(clock.instant())).isZero();
        clock.freezeAt(Instant.parse("2030-03-15T03:59:00Z")); // 10:59
        assertThat(planner.planDigests(clock.instant())).isEqualTo(1);
    }

    @Test
    void morning_followsTheAccountTimeZone() throws Exception {
        RegisteredUser user = userWith("{\"morningOverview\": true}");
        testData.setTimeZone(user.id(), "America/New_York");
        clock.freezeAt(Instant.parse("2030-03-15T01:00:00Z")); // 08:00 ở Việt Nam, nhưng 21:00 hôm trước ở New York
        assertThat(planner.planDigests(clock.instant())).isZero();
        clock.freezeAt(Instant.parse("2030-03-15T12:00:00Z")); // 08:00 EDT
        assertThat(planner.planDigests(clock.instant())).isEqualTo(1);
    }

    @Test
    void morning_emptyDayAndEnglishLocale() throws Exception {
        RegisteredUser user = userWith("{\"morningOverview\": true}");
        jdbcTemplate.update("update users set locale = 'en' where id = ?", user.id());
        clock.freezeAt(MORNING);
        planner.planDigests(clock.instant());
        delivery.deliverDue();
        assertThat(mails(user)).hasSize(1);
        assertThat(mails(user).get(0).subject()).isEqualTo("Taskly - Your day: 0 task(s)");
        assertThat(mails(user).get(0).body()).contains("Nothing is due today");
    }

    @Test
    void morning_capsTheListAndCountsTheRest() throws Exception {
        RegisteredUser user = userWith("{\"morningOverview\": true}");
        for (int i = 1; i <= 23; i++) {
            open(user, "Việc số %02d".formatted(i), TODAY);
        }
        clock.freezeAt(MORNING);
        planner.planDigests(clock.instant());
        delivery.deliverDue();
        OutgoingMail mail = mails(user).get(0);
        assertThat(mail.subject()).contains("23 việc");
        assertThat(mail.body().lines().filter(line -> line.startsWith("- ")).count()).isEqualTo(20);
        assertThat(mail.body()).contains("...và 3 việc khác");
    }

    @Test
    void digests_skipUsersWhoDidNotOptIn() throws Exception {
        RegisteredUser off = userWith("{\"subscribedEmails\": false}");
        RegisteredUser eveningOnly = userWith("{\"eveningReview\": true}");
        open(off, "Việc", TODAY);
        open(eveningOnly, "Việc", TODAY);
        clock.freezeAt(MORNING);
        assertThat(planner.planDigests(clock.instant())).isZero();
        delivery.deliverDue();
        assertThat(mails(off)).isEmpty();
        assertThat(mails(eveningOnly)).isEmpty();
    }

    // ---- Evening review ----

    @Test
    void evening_summarizesDoneStillOpenAndTomorrow() throws Exception {
        RegisteredUser user = userWith("{\"eveningReview\": true}");
        Instant evening = Instant.parse("2030-03-15T14:00:00Z"); // 21:00 địa phương
        UUID inbox = testData.inboxOf(user.id());
        testData.insert(TaskRow.of(user.id(), inbox, "Xong sáng").completedAt(Instant.parse("2030-03-15T02:00:00Z")));
        testData.insert(TaskRow.of(user.id(), inbox, "Xong chiều").completedAt(Instant.parse("2030-03-15T09:00:00Z")));
        testData.insert(TaskRow.of(user.id(), inbox, "Xong hôm qua").completedAt(Instant.parse("2030-03-14T09:00:00Z")));
        open(user, "Còn dở", TODAY);
        open(user, "Trễ", TODAY.minusDays(1));
        open(user, "Mai làm", TODAY.plusDays(1));
        clock.freezeAt(evening);

        assertThat(planner.planDigests(clock.instant())).isEqualTo(1);
        delivery.deliverDue();

        OutgoingMail mail = mails(user).get(0);
        assertThat(mail.subject()).isEqualTo("Taskly - Tổng kết tối: đã xong 2 việc hôm nay");
        assertThat(mail.body()).contains("Còn mở (đến hạn hôm nay hoặc quá hạn): 2", "- Còn dở", "- Trễ (quá hạn)",
                "Đến hạn ngày mai: 1");
        assertThat(planner.planDigests(clock.instant())).isZero();
    }

    @Test
    void evening_notBeforeTwentyHundred() throws Exception {
        userWith("{\"eveningReview\": true}");
        clock.freezeAt(Instant.parse("2030-03-15T12:59:00Z")); // 19:59 địa phương
        assertThat(planner.planDigests(clock.instant())).isZero();
        clock.freezeAt(Instant.parse("2030-03-15T13:00:00Z")); // 20:00
        assertThat(planner.planDigests(clock.instant())).isGreaterThanOrEqualTo(1);
    }

    // ---- nhắc việc ----

    /** Task có giờ 09:30 địa phương: auto reminder mặc định 30 phút nên nhắc lúc 09:00. */
    private UUID taskDueAtNineThirty(RegisteredUser user) {
        return testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), "Họp nhóm")
                .dueDatetime(LocalDateTime.of(2030, 3, 15, 9, 30)));
    }

    @Test
    void reminders_goToPushAndEmail_onceAtTheFireTime() throws Exception {
        RegisteredUser user = userWith("{\"reminderPush\": true, \"reminderEmail\": true}");
        String device = "device-" + UUID.randomUUID();
        authPost(user.accessToken(), "/api/v1/me/push-tokens", "{\"token\": \"%s\", \"platform\": \"ANDROID\"}"
                .formatted(device));
        UUID taskId = taskDueAtNineThirty(user);

        clock.freezeAt(Instant.parse("2030-03-15T01:59:00Z")); // 08:59, chưa tới giờ nhắc
        assertThat(planner.planReminders(clock.instant())).isZero();

        clock.freezeAt(Instant.parse("2030-03-15T02:01:00Z")); // 09:01, nhắc lúc 09:00 vừa qua
        assertThat(planner.planReminders(clock.instant())).isEqualTo(2);
        assertThat(planner.planReminders(clock.instant())).isZero(); // khoá chống gửi trùng
        delivery.deliverDue();

        assertThat(pushSender.sentTo(device)).hasSize(1);
        assertThat(pushSender.sentTo(device).get(0).title()).isEqualTo("Họp nhóm");
        assertThat(pushSender.sentTo(device).get(0).data()).containsEntry("taskId", taskId.toString())
                .containsEntry("kind", "reminder");
        assertThat(mails(user)).hasSize(1);
        assertThat(mails(user).get(0).subject()).isEqualTo("Taskly - Nhắc việc: Họp nhóm");

        clock.freezeAt(Instant.parse("2030-03-15T02:02:00Z"));
        assertThat(planner.planReminders(clock.instant())).isZero();
        clock.freezeAt(Instant.parse("2030-03-15T02:10:00Z")); // quá 5 phút kể từ lần nhắc
        assertThat(planner.planReminders(clock.instant())).isZero();
    }

    @Test
    void reminders_respectTheChannelSwitches() throws Exception {
        RegisteredUser pushOnly = userWith("{\"reminderPush\": true}");
        RegisteredUser emailOnly = userWith("{\"reminderEmail\": true}");
        RegisteredUser none = userWith("{\"morningOverview\": true}");
        for (RegisteredUser user : List.of(pushOnly, emailOnly, none)) {
            taskDueAtNineThirty(user);
        }
        authPost(pushOnly.accessToken(), "/api/v1/me/push-tokens", "{\"token\": \"p-%s\", \"platform\": \"ANDROID\"}"
                .formatted(pushOnly.id()));
        clock.freezeAt(Instant.parse("2030-03-15T02:01:00Z"));

        assertThat(planner.planReminders(clock.instant())).isEqualTo(2);
        delivery.deliverDue();
        assertThat(pushSender.sentTo("p-" + pushOnly.id())).hasSize(1);
        assertThat(mails(pushOnly)).isEmpty();
        assertThat(mails(emailOnly)).hasSize(1);
        assertThat(mails(none)).isEmpty();
    }

    @Test
    void reminders_skipDoneTasksAndTasksWithoutTime() throws Exception {
        RegisteredUser user = userWith("{\"reminderEmail\": true}");
        UUID inbox = testData.inboxOf(user.id());
        testData.insert(TaskRow.of(user.id(), inbox, "Đã xong").dueDatetime(LocalDateTime.of(2030, 3, 15, 9, 30))
                .completedAt(Instant.parse("2030-03-15T01:00:00Z")));
        testData.insert(TaskRow.of(user.id(), inbox, "Không giờ").dueDate(TODAY));
        clock.freezeAt(Instant.parse("2030-03-15T02:01:00Z"));
        assertThat(planner.planReminders(clock.instant())).isZero();
    }

    @Test
    void reminders_includeCustomAbsoluteReminders() throws Exception {
        RegisteredUser user = userWith("{\"reminderEmail\": true}");
        jdbcTemplate.update("update users set auto_reminder_minutes = null where id = ?", user.id());
        UUID taskId = taskDueAtNineThirty(user);
        jdbcTemplate.update("insert into reminders (id, task_id, user_id, type, at, created_at, updated_at) "
                        + "values (?, ?, ?, 'ABSOLUTE', ?, now(), now())", UUID.randomUUID(), taskId, user.id(),
                java.sql.Timestamp.valueOf(LocalDateTime.of(2030, 3, 15, 8, 0)));
        clock.freezeAt(Instant.parse("2030-03-15T01:01:00Z")); // 08:01
        assertThat(planner.planReminders(clock.instant())).isEqualTo(1);
    }

    // ---- hàng đợi: chống trùng, thử lại, token hết hiệu lực ----

    @Test
    void enqueue_dedupesByUserChannelAndKey() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        assertThat(delivery.enqueue(user.id(), "EMAIL", "TEST", "k1", "t", "b", null)).isTrue();
        assertThat(delivery.enqueue(user.id(), "EMAIL", "TEST", "k1", "t", "b", null)).isFalse();
        assertThat(delivery.enqueue(user.id(), "PUSH", "TEST", "k1", "t", "b", Map.of("a", "b"))).isTrue();
        assertThat(delivery.enqueue(user.id(), "EMAIL", "TEST", "k2", "t", "b", null)).isTrue();
    }

    @Test
    void email_failure_retriesThreeTimesThenFails_andRecovers() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        mailbox.setFailing(true);
        delivery.enqueue(user.id(), "EMAIL", "TEST", "retry", "Tiêu đề", "Nội dung", null);

        delivery.deliverDue();
        assertThat(row(user, "retry")).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        delivery.deliverDue(); // chưa tới hạn
        assertThat(row(user, "retry")).containsEntry("attempts", 1);
        clock.advance(Duration.ofMinutes(1));
        delivery.deliverDue();
        clock.advance(Duration.ofMinutes(5));
        delivery.deliverDue();
        assertThat(row(user, "retry")).containsEntry("status", "PENDING").containsEntry("attempts", 3);
        mailbox.setFailing(false);
        clock.advance(Duration.ofMinutes(15));
        delivery.deliverDue();
        assertThat(row(user, "retry")).containsEntry("status", "SENT").containsEntry("attempts", 4);
        assertThat(mails(user)).hasSize(1);
    }

    @Test
    void email_failure_endsFailedAfterTheThirdRetry() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        mailbox.setFailing(true);
        delivery.enqueue(user.id(), "EMAIL", "TEST", "dead", "t", "b", null);
        for (Duration wait : List.of(Duration.ZERO, Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15))) {
            clock.advance(wait);
            delivery.deliverDue();
        }
        assertThat(row(user, "dead")).containsEntry("status", "FAILED").containsEntry("attempts", 4);
        clock.advance(Duration.ofHours(3));
        delivery.deliverDue();
        assertThat(row(user, "dead")).containsEntry("attempts", 4);
    }

    @Test
    void push_transientFailureRetries_invalidTokenIsRemoved_noDevicesIsDone() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String device = "d-" + UUID.randomUUID();
        authPost(user.accessToken(), "/api/v1/me/push-tokens", "{\"token\": \"%s\", \"platform\": \"ANDROID\"}"
                .formatted(device));

        pushSender.setFailing(true);
        delivery.enqueue(user.id(), "PUSH", "TEST", "p1", "t", "b", null);
        delivery.deliverDue();
        assertThat(row(user, "p1")).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        pushSender.setFailing(false);
        clock.advance(Duration.ofMinutes(1));
        delivery.deliverDue();
        assertThat(row(user, "p1")).containsEntry("status", "SENT");
        assertThat(pushSender.sentTo(device)).hasSize(1);

        pushSender.invalidate(device);
        delivery.enqueue(user.id(), "PUSH", "TEST", "p2", "t", "b", null);
        delivery.deliverDue();
        assertThat(row(user, "p2")).containsEntry("status", "SENT");
        assertThat(jdbcTemplate.queryForObject("select count(*) from push_tokens where user_id = ?", Integer.class,
                user.id())).isZero();

        delivery.enqueue(user.id(), "PUSH", "TEST", "p3", "t", "b", null); // không còn thiết bị
        delivery.deliverDue();
        assertThat(row(user, "p3")).containsEntry("status", "SENT");
    }

    private Map<String, Object> row(RegisteredUser user, String key) {
        return jdbcTemplate.queryForMap(
                "select status, attempts from notification_deliveries where user_id = ? and dedupe_key = ?",
                user.id(), key);
    }
}
