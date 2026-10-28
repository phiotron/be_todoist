package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.CalendarIntegrationTestBase;
import com.taskly.support.TestData.TaskRow;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** Calendar feed iCal (M13b): link có token, tài khoản / dự án, task lặp tối đa 7 lần, đặt lại link. */
class CalendarFeedApiTest extends CalendarIntegrationTestBase {

    private static final String BASE = "https://taskly.test";

    private String feedUrl(String accessToken, String json) throws Exception {
        return JsonPath.read(body(authPost(accessToken, "/api/v1/me/calendar-feeds", json == null ? "{}" : json)
                .andExpect(status().isOk()).andReturn()), "$.url");
    }

    private String fetch(String url) throws Exception {
        assertThat(url).startsWith(BASE).endsWith(".ics");
        return body(mockMvc.perform(get(url.substring(BASE.length()))
                        // app có thể gửi kèm access token hết hạn — đường feed bỏ qua Authorization
                        .header(HttpHeaders.AUTHORIZATION, "Bearer junk"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith("text/calendar"))
                .andReturn());
    }

    @Test
    void accountFeedListsScheduledTasksAndIsStable() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        testData.setTimeZone(user.id(), "Asia/Ho_Chi_Minh");
        LocalDate day = LocalDate.now().plusDays(3);
        UUID allDay = testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), "Mua sữa; cà phê, trà")
                .dueDate(day));
        UUID timed = testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), "Họp")
                .dueDatetime(day.atTime(9, 0)).durationMinutes(30));
        testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), "Không ngày"));
        testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), "Xong").dueDate(day)
                .completedAt(java.time.Instant.now()));

        String url = feedUrl(user.accessToken(), null);
        String ics = fetch(url);

        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\n").endsWith("END:VCALENDAR\r\n");
        assertThat(ics).contains("UID:" + allDay + "@taskly", "UID:" + timed + "@taskly")
                .contains("SUMMARY:Mua sữa\\; cà phê\\, trà")
                .contains("DTSTART;VALUE=DATE:" + day.toString().replace("-", ""))
                .contains("DTSTART:" + day.toString().replace("-", "") + "T020000Z") // 09:00 +07 = 02:00Z
                .contains("DTEND:" + day.toString().replace("-", "") + "T023000Z")
                .doesNotContain("Không ngày").doesNotContain("SUMMARY:Xong");
        // Gọi lại trả cùng link (không sinh token mới).
        assertThat(feedUrl(user.accessToken(), "{}")).isEqualTo(url);
        assertThat(jdbcTemplate.queryForObject("select count(*) from calendar_feeds where user_id = ?", Integer.class,
                user.id())).isEqualTo(1);
        // Token không lưu rõ trong DB.
        String token = url.substring(url.lastIndexOf('/') + 1, url.length() - 4);
        assertThat(jdbcTemplate.queryForObject("select token_hash || token_enc from calendar_feeds where user_id = ?",
                String.class, user.id())).doesNotContain(token);
    }

    @Test
    void recurringTasksShowAtMostSevenOccurrences() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        LocalDate day = LocalDate.now().plusDays(1);
        UUID daily = testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), "Hằng ngày").dueDate(day));
        jdbcTemplate.update("update tasks set recurrence_rule = 'FREQ=DAILY;COUNT=30' where id = ?", daily);
        UUID until = testData.insert(TaskRow.of(user.id(), testData.inboxOf(user.id()), "Có hạn").dueDate(day));
        jdbcTemplate.update("update tasks set recurrence_rule = 'FREQ=WEEKLY;UNTIL=20271231' where id = ?", until);

        String ics = fetch(feedUrl(user.accessToken(), null));

        assertThat(ics).contains("RRULE:FREQ=DAILY;COUNT=7").doesNotContain("COUNT=30")
                .contains("RRULE:FREQ=WEEKLY;UNTIL=20271231").doesNotContain("UNTIL=20271231;COUNT");
    }

    @Test
    void resetInvalidatesTheOldLink() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String oldUrl = feedUrl(user.accessToken(), null);

        String newUrl = JsonPath.read(body(authPost(user.accessToken(), "/api/v1/me/calendar-feeds/reset", "{}")
                .andExpect(status().isOk()).andReturn()), "$.url");

        assertThat(newUrl).isNotEqualTo(oldUrl);
        mockMvc.perform(get(oldUrl.substring(BASE.length()))).andExpect(status().isNotFound());
        fetch(newUrl);
    }

    @Test
    void projectFeedOnlyHasThatProjectAndNeedsReadAccess() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID p1 = testData.project(user.id(), "Dự án một");
        UUID p2 = testData.project(user.id(), "Dự án hai");
        LocalDate day = LocalDate.now().plusDays(2);
        testData.insert(TaskRow.of(user.id(), p1, "Trong một").dueDate(day));
        testData.insert(TaskRow.of(user.id(), p2, "Trong hai").dueDate(day));

        String url = feedUrl(user.accessToken(), "{\"projectId\": \"%s\"}".formatted(p1));
        String ics = fetch(url);

        assertThat(ics).contains("SUMMARY:Trong một").doesNotContain("Trong hai").contains("Dự án một");
        assertThat(url).isNotEqualTo(feedUrl(user.accessToken(), null));
        authPost(user.accessToken(), "/api/v1/me/calendar-feeds", "{\"projectId\": \"%s\"}".formatted(UUID.randomUUID()))
                .andExpect(status().isNotFound());
        RegisteredUser other = registerUser(uniqueEmail());
        authPost(other.accessToken(), "/api/v1/me/calendar-feeds", "{\"projectId\": \"%s\"}".formatted(p1))
                .andExpect(status().isNotFound());
        // Dự án bị xoá → link thành 404.
        testData.deleteProject(p1);
        mockMvc.perform(get(url.substring(BASE.length()))).andExpect(status().isNotFound());
    }

    @Test
    void unknownTokensAre404AndEndpointsNeedLogin() throws Exception {
        mockMvc.perform(get("/api/v1/calendar/feed/not-a-token.ics")).andExpect(status().isNotFound());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/me/calendar-feeds")).andExpect(status().isUnauthorized());
        RegisteredUser user = registerUser(uniqueEmail());
        authPost(user.accessToken(), "/api/v1/me/calendar-feeds", "{\"projectId\": \"x\"}")
                .andExpect(status().isBadRequest());
        authPost(user.accessToken(), "/api/v1/me/calendar-feeds", "{}").andExpect(jsonPath("$.projectId").isEmpty());
    }
}
