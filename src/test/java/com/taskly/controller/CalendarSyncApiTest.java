package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.CalendarIntegrationTestBase;
import com.taskly.entity.CalendarProvider;
import com.taskly.integration.calendar.ExternalEvent;
import com.taskly.support.FakeCalendarClient;
import com.taskly.support.TestData.TaskRow;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** "Sync tasks to calendar" (M13b): đẩy task có lịch, kéo ngược có giới hạn, KHÔNG hai chiều đầy đủ. */
class CalendarSyncApiTest extends CalendarIntegrationTestBase {

    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    private RegisteredUser connectedUser(CalendarProvider provider, String calendarEmail, boolean sync) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        testData.setTimeZone(user.id(), ZONE.getId());
        connectCalendar(user.accessToken(), provider, calendarEmail);
        if (sync) {
            patchCalendar(user.accessToken(), "{\"syncTasks\": true}").andExpect(status().isOk());
        }
        return user;
    }

    private void sync(RegisteredUser user, int created, int updated, int deleted, int pulled) throws Exception {
        authPost(user.accessToken(), "/api/v1/me/calendar/sync").andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(created)).andExpect(jsonPath("$.updated").value(updated))
                .andExpect(jsonPath("$.deleted").value(deleted)).andExpect(jsonPath("$.pulled").value(pulled));
    }

    @Test
    void syncNeedsAConnection() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPost(user.accessToken(), "/api/v1/me/calendar/sync").andExpect(status().isNotFound());
    }

    @Test
    void pushesOnlyEligibleTasksToTheTasklyCalendar() throws Exception {
        String email = uniqueCalendarEmail();
        RegisteredUser user = connectedUser(CalendarProvider.GOOGLE, email, true);
        UUID project = testData.project(user.id(), "Việc nhà");
        LocalDate day = today().plusDays(2);
        testData.insert(TaskRow.of(user.id(), project, "Cả ngày").dueDate(day));
        testData.insert(TaskRow.of(user.id(), project, "Có giờ").dueDatetime(day.atTime(9, 0)).durationMinutes(45));
        testData.insert(TaskRow.of(user.id(), project, "Không ngày"));
        testData.insert(TaskRow.of(user.id(), project, "Đã xong").dueDate(day).completedAt(Instant.now()));
        testData.insert(TaskRow.of(user.id(), project, "Xa quá").dueDate(today().plusDays(900)));
        UUID archived = testData.project(user.id(), "Cũ");
        testData.insert(TaskRow.of(user.id(), archived, "Trong dự án lưu trữ").dueDate(day));
        testData.archiveProject(archived);
        // Task của người khác không bao giờ vào lịch của mình.
        RegisteredUser other = registerUser(uniqueEmail());
        testData.insert(TaskRow.of(other.id(), testData.inboxOf(other.id()), "Của người khác").dueDate(day));

        sync(user, 2, 0, 0, 0);

        FakeCalendarClient.Account account = fake(CalendarProvider.GOOGLE).account(email);
        assertThat(account.taskly.values()).extracting(s -> s.draft().title())
                .containsExactlyInAnyOrder("Cả ngày", "Có giờ");
        var timed = account.taskly.values().stream().filter(s -> s.draft().title().equals("Có giờ")).findFirst()
                .orElseThrow().draft();
        assertThat(timed.allDay()).isFalse();
        assertThat(timed.start()).isEqualTo(day.atTime(9, 0).atZone(ZONE).toInstant());
        assertThat(Duration.between(timed.start(), timed.end())).isEqualTo(Duration.ofMinutes(45));
        assertThat(account.taskly.values().stream().filter(s -> s.draft().allDay()).findFirst().orElseThrow()
                .draft().description()).contains("taskly://task/");
        authGet(user.accessToken(), "/api/v1/me/calendar").andExpect(jsonPath("$.lastSyncedAt").isNotEmpty());
        // Chạy lại không đổi gì → không tạo trùng.
        sync(user, 0, 0, 0, 0);
        assertThat(account.taskly).hasSize(2);
    }

    @Test
    void changesDeletionsAndTurningSyncOffAreMirrored() throws Exception {
        String email = uniqueCalendarEmail();
        RegisteredUser user = connectedUser(CalendarProvider.OUTLOOK, email, true);
        UUID project = testData.project(user.id(), "P");
        LocalDate day = today().plusDays(1);
        UUID a = testData.insert(TaskRow.of(user.id(), project, "A").dueDate(day));
        UUID b = testData.insert(TaskRow.of(user.id(), project, "B").dueDate(day));
        UUID c = testData.insert(TaskRow.of(user.id(), project, "C").dueDate(day));
        sync(user, 3, 0, 0, 0);
        FakeCalendarClient.Account account = fake(CalendarProvider.OUTLOOK).account(email);

        authPatch(user.accessToken(), "/api/v1/tasks/" + a, "{\"version\": %d, \"content\": \"A mới\"}"
                .formatted(testData.taskVersion(a))).andExpect(status().isOk());
        authPost(user.accessToken(), "/api/v1/tasks/" + b + "/close").andExpect(status().is2xxSuccessful());
        testData.deleteTask(c);
        sync(user, 0, 1, 2, 0);
        assertThat(account.taskly.values()).extracting(s -> s.draft().title()).containsExactly("A mới");

        patchCalendar(user.accessToken(), "{\"syncTasks\": false}").andExpect(status().isOk());
        sync(user, 0, 0, 1, 0);
        assertThat(account.taskly).isEmpty();
        assertThat(jdbcTemplate.queryForObject("select count(*) from calendar_task_links l join calendar_connections c "
                + "on c.id = l.connection_id where c.user_id = ?", Integer.class, user.id())).isZero();
    }

    @Test
    void deletedEventInTheCalendarIsRecreated() throws Exception {
        String email = uniqueCalendarEmail();
        RegisteredUser user = connectedUser(CalendarProvider.GOOGLE, email, true);
        UUID task = testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), "Sửa tên")
                .dueDate(today().plusDays(1)));
        sync(user, 1, 0, 0, 0);
        FakeCalendarClient.Account account = fake(CalendarProvider.GOOGLE).account(email);
        account.taskly.clear(); // người dùng xoá sự kiện trong lịch
        authPatch(user.accessToken(), "/api/v1/tasks/" + task, "{\"version\": %d, \"content\": \"Sửa tên 2\"}"
                .formatted(testData.taskVersion(task))).andExpect(status().isOk());

        sync(user, 0, 1, 0, 0);

        assertThat(account.taskly.values()).extracting(s -> s.draft().title()).containsExactly("Sửa tên 2");
    }

    @Test
    void movingAnEventInTheCalendarMovesTheNonRecurringTask() throws Exception {
        String email = uniqueCalendarEmail();
        RegisteredUser user = connectedUser(CalendarProvider.GOOGLE, email, true);
        LocalDate day = today().plusDays(3);
        UUID task = testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), "Kéo ngược")
                .dueDatetime(day.atTime(9, 0)));
        sync(user, 1, 0, 0, 0);
        FakeCalendarClient.Account account = fake(CalendarProvider.GOOGLE).account(email);
        String eventId = account.taskly.keySet().iterator().next();
        // Người dùng dời sang 14:00 và kéo dài 90 phút trong lịch "Taskly".
        Instant newStart = day.atTime(14, 0).atZone(ZONE).toInstant();
        fake(CalendarProvider.GOOGLE).userMoves(email, eventId,
                ExternalEvent.timed(eventId, "Kéo ngược", newStart, newStart.plus(Duration.ofMinutes(90))));

        sync(user, 0, 1, 0, 1);

        authGet(user.accessToken(), "/api/v1/tasks/" + task).andExpect(status().isOk())
                .andExpect(jsonPath("$.dueDatetime").value(day.atTime(14, 0) + ":00"))
                .andExpect(jsonPath("$.durationMinutes").value(90));
        // Sự kiện được đẩy lại theo task đã đổi (độ dài 90 phút).
        assertThat(Duration.between(account.taskly.get(eventId).draft().start(), account.taskly.get(eventId)
                .draft().end())).isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void movingAnEventToAnotherDayChangesAnAllDayTask() throws Exception {
        String email = uniqueCalendarEmail();
        RegisteredUser user = connectedUser(CalendarProvider.OUTLOOK, email, true);
        LocalDate day = today().plusDays(4);
        UUID task = testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), "Cả ngày").dueDate(day));
        sync(user, 1, 0, 0, 0);
        String eventId = fake(CalendarProvider.OUTLOOK).account(email).taskly.keySet().iterator().next();
        fake(CalendarProvider.OUTLOOK).userMoves(email, eventId,
                ExternalEvent.allDay(eventId, "Cả ngày", day.plusDays(2), day.plusDays(3)));

        sync(user, 0, 1, 0, 1);

        authGet(user.accessToken(), "/api/v1/tasks/" + task).andExpect(jsonPath("$.dueDate")
                .value(day.plusDays(2).toString()));
    }

    @Test
    void recurringTasksAndTasksEditedInTasklyIgnoreTheCalendarChange() throws Exception {
        String email = uniqueCalendarEmail();
        RegisteredUser user = connectedUser(CalendarProvider.GOOGLE, email, true);
        LocalDate day = today().plusDays(5);
        UUID inbox = testData.inboxOf(user.id());
        UUID recurring = testData.insert(TaskRow.of(user.id(), inbox, "Lặp").dueDate(day));
        jdbcTemplate.update("update tasks set recurrence_rule = 'FREQ=DAILY', due_string = 'every day' where id = ?",
                recurring);
        UUID edited = testData.insert(TaskRow.of(user.id(), inbox, "Sửa ở Taskly").dueDate(day));
        sync(user, 2, 0, 0, 0);
        FakeCalendarClient.Account account = fake(CalendarProvider.GOOGLE).account(email);
        // Taskly sửa nội dung SAU lần đẩy (version tăng) rồi lịch cũng dời ngày → Taskly thắng.
        authPatch(user.accessToken(), "/api/v1/tasks/" + edited, "{\"version\": %d, \"content\": \"Sửa ở Taskly 2\"}"
                .formatted(testData.taskVersion(edited))).andExpect(status().isOk());
        for (var stored : account.taskly.values()) {
            fake(CalendarProvider.GOOGLE).userMoves(email, stored.id(),
                    ExternalEvent.allDay(stored.id(), stored.draft().title(), day.plusDays(3), day.plusDays(4)));
        }

        // pulled = 0; sự kiện bị đẩy đè lại theo Taskly (task đã sửa nội dung → 1 update).
        sync(user, 0, 1, 0, 0);

        authGet(user.accessToken(), "/api/v1/tasks/" + recurring).andExpect(jsonPath("$.dueDate")
                .value(day.toString()));
        authGet(user.accessToken(), "/api/v1/tasks/" + edited).andExpect(jsonPath("$.dueDate")
                .value(day.toString()));
    }

    @Test
    void revokedAccessMarksReauthAndOutageIs503() throws Exception {
        String email = uniqueCalendarEmail();
        RegisteredUser user = connectedUser(CalendarProvider.GOOGLE, email, true);
        testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), "X").dueDate(today().plusDays(1)));
        FakeCalendarClient.Account account = fake(CalendarProvider.GOOGLE).account(email);

        account.unavailable = true;
        authPost(user.accessToken(), "/api/v1/me/calendar/sync").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("calendar-unavailable"));
        account.unavailable = false;

        account.revoked = true;
        sync(user, 0, 0, 0, 0);
        authGet(user.accessToken(), "/api/v1/me/calendar").andExpect(jsonPath("$.needsReauth").value(true));
    }

    @Test
    void backgroundJobSyncsEveryEnabledConnection() throws Exception {
        String email = uniqueCalendarEmail();
        RegisteredUser user = connectedUser(CalendarProvider.GOOGLE, email, true);
        testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), "Job").dueDate(today().plusDays(1)));

        syncAll();

        assertThat(fake(CalendarProvider.GOOGLE).account(email).taskly).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("select last_synced_at from calendar_connections where user_id = ?",
                Timestamp.class, user.id())).isNotNull();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.taskly.service.CalendarSyncService syncService;

    private void syncAll() {
        syncService.syncAll();
    }
}
