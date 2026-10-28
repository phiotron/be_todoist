package com.taskly.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.LimitKind;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * M12: nhắc theo vị trí (type LOCATION + locationId + trigger ON_ENTER / ON_LEAVE), cờ urgent của nhắc theo giờ,
 * GET /api/v1/tasks/location-reminders (geofence cho app) và urgent trong GET /api/v1/tasks/reminders.
 */
class LocationReminderApiTest extends IntegrationTestBase {

    /** 10:00 ngày 23/10/2026 ở Asia/Ho_Chi_Minh. */
    private static final Instant TEN_AM_HCM = Instant.parse("2026-10-23T03:00:00Z");
    private static final String LOCATION_FIELDS_VI =
            "Nhắc theo vị trí cần locationId và trigger (không có at, minuteOffset)";
    private static final String URGENT_LOCATION_VI = "Nhắc theo vị trí không đặt khẩn cấp được";

    private static String reminders(UUID taskId) {
        return "/api/v1/tasks/" + taskId + "/reminders";
    }

    private RegisteredUser frozenUser() throws Exception {
        clock.freezeAt(TEN_AM_HCM);
        return registerUser(uniqueEmail());
    }

    private UUID task(RegisteredUser user, String json) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/tasks", json).andExpect(status().isCreated()), "$.id");
    }

    private UUID plainTask(RegisteredUser user, String content) throws Exception {
        return task(user, """
                {"content": "%s"}
                """.formatted(content));
    }

    private UUID location(RegisteredUser user, String name) throws Exception {
        return readUuid(authPost(user.accessToken(), "/api/v1/locations", """
                {"name": "%s", "latitude": 10.7769, "longitude": 106.7009, "radius": 200}
                """.formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    private ResultActions postReminder(RegisteredUser user, UUID taskId, String json) throws Exception {
        return authPost(user.accessToken(), reminders(taskId), json);
    }

    private String locationReminder(UUID locationId, String trigger) {
        return """
                {"type": "LOCATION", "locationId": "%s", "trigger": "%s"}
                """.formatted(locationId, trigger);
    }

    // ---- tạo nhắc vị trí ----------------------------------------------------------------------------------

    @Test
    void createLocation_onTaskWithoutDue_returns201() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = plainTask(user, "Mua sữa");
        UUID locationId = location(user, "Siêu thị");

        postReminder(user, taskId, locationReminder(locationId, "ON_ENTER"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("LOCATION"))
                .andExpect(jsonPath("$.locationId").value(locationId.toString()))
                .andExpect(jsonPath("$.locationTrigger").value("ON_ENTER"))
                .andExpect(jsonPath("$.at").value(nullValue()))
                .andExpect(jsonPath("$.minuteOffset").value(nullValue()))
                .andExpect(jsonPath("$.urgent").value(false));

        authGet(user.accessToken(), reminders(taskId))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].locationTrigger").value("ON_ENTER"));
    }

    @Test
    void createLocation_withTimeFieldsOrWithoutLocation_returns400() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = plainTask(user, "Gọi điện");
        UUID locationId = location(user, "Nhà");

        for (String json : new String[] {
            """
            {"type": "LOCATION", "trigger": "ON_ENTER"}
            """,
            """
            {"type": "LOCATION", "locationId": "%s"}
            """.formatted(locationId),
            """
            {"type": "LOCATION", "locationId": "%s", "trigger": "ON_LEAVE", "at": "2026-10-23T11:00"}
            """.formatted(locationId),
            """
            {"type": "LOCATION", "locationId": "%s", "trigger": "ON_LEAVE", "minuteOffset": 5}
            """.formatted(locationId)}) {
            postReminder(user, taskId, json)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(LOCATION_FIELDS_VI));
        }
        authGet(user.accessToken(), reminders(taskId)).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void timeReminderWithLocationFields_returns400() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = plainTask(user, "Đọc sách");
        UUID locationId = location(user, "Thư viện");

        postReminder(user, taskId, """
                {"type": "ABSOLUTE", "at": "2026-10-23T11:00", "locationId": "%s"}
                """.formatted(locationId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(LOCATION_FIELDS_VI));
        postReminder(user, taskId, """
                {"type": "ABSOLUTE", "at": "2026-10-23T11:00", "trigger": "ON_ENTER"}
                """)
                .andExpect(status().isBadRequest());
    }

    @Test
    void createLocation_withSomeoneElsesLocation_returns404() throws Exception {
        RegisteredUser user = frozenUser();
        RegisteredUser other = registerUser(uniqueEmail());
        UUID taskId = plainTask(user, "Việc");
        UUID foreign = location(other, "Nhà người khác");

        postReminder(user, taskId, locationReminder(foreign, "ON_ENTER"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Không tìm thấy địa điểm"));
        postReminder(user, taskId, locationReminder(UUID.randomUUID(), "ON_ENTER"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createLocation_unknownTrigger_returns400() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = plainTask(user, "Việc");
        UUID locationId = location(user, "Nhà");

        postReminder(user, taskId, locationReminder(locationId, "NEARBY")).andExpect(status().isBadRequest());
    }

    @Test
    void createLocation_countsTowardsTheReminderLimit() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = plainTask(user, "Việc");
        UUID locationId = location(user, "Nhà");
        usageLimits.overrideMax(LimitKind.REMINDERS, 1);
        postReminder(user, taskId, locationReminder(locationId, "ON_ENTER")).andExpect(status().isCreated());

        postReminder(user, taskId, locationReminder(locationId, "ON_LEAVE"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.limit").value("REMINDERS"));
    }

    // ---- urgent ------------------------------------------------------------------------------------------

    @Test
    void urgentTimeReminder_isStoredAndReturned() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = task(user, """
                {"content": "Uống thuốc", "dueDatetime": "2026-10-23T12:00:00"}
                """);

        postReminder(user, taskId, """
                {"type": "ABSOLUTE", "at": "2026-10-23T11:30", "urgent": true}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.urgent").value(true))
                .andExpect(jsonPath("$.locationId").value(nullValue()))
                .andExpect(jsonPath("$.locationTrigger").value(nullValue()));
        postReminder(user, taskId, """
                {"type": "RELATIVE", "minuteOffset": 10}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.urgent").value(false));
    }

    @Test
    void urgentLocationReminder_returns400() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = plainTask(user, "Việc");
        UUID locationId = location(user, "Nhà");

        postReminder(user, taskId, """
                {"type": "LOCATION", "locationId": "%s", "trigger": "ON_ENTER", "urgent": true}
                """.formatted(locationId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(URGENT_LOCATION_VI));
    }

    @Test
    void occurrences_carryTheUrgentFlag_andSkipLocationReminders() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = task(user, """
                {"content": "Họp", "dueDatetime": "2026-10-23T12:00:00"}
                """);
        UUID locationId = location(user, "Văn phòng");
        postReminder(user, taskId, """
                {"type": "ABSOLUTE", "at": "2026-10-23T10:30", "urgent": true}
                """).andExpect(status().isCreated());
        postReminder(user, taskId, locationReminder(locationId, "ON_ENTER")).andExpect(status().isCreated());

        authGet(user.accessToken(), "/api/v1/tasks/reminders")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].kind").value("ABSOLUTE"))
                .andExpect(jsonPath("$[0].urgent").value(true))
                .andExpect(jsonPath("$[1].kind").value("AUTO"))
                .andExpect(jsonPath("$[1].urgent").value(false));
    }

    // ---- GET tasks/location-reminders ---------------------------------------------------------------------

    @Test
    void locationReminders_listOpenTasksOfMine_withTheirPlace() throws Exception {
        RegisteredUser user = frozenUser();
        RegisteredUser other = registerUser(uniqueEmail());
        UUID open = plainTask(user, "Mua sữa");
        UUID done = plainTask(user, "Đã xong");
        UUID locationId = location(user, "Siêu thị");
        UUID reminderId = readUuid(postReminder(user, open, locationReminder(locationId, "ON_LEAVE"))
                .andExpect(status().isCreated()), "$.id");
        clock.advance(Duration.ofSeconds(1));
        postReminder(user, done, locationReminder(locationId, "ON_ENTER")).andExpect(status().isCreated());
        authPost(user.accessToken(), "/api/v1/tasks/" + done + "/close").andExpect(status().isOk());
        postReminder(other, plainTask(other, "Của người khác"), locationReminder(location(other, "X"), "ON_ENTER"))
                .andExpect(status().isCreated());

        authGet(user.accessToken(), "/api/v1/tasks/location-reminders")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reminderId").value(reminderId.toString()))
                .andExpect(jsonPath("$[0].taskId").value(open.toString()))
                .andExpect(jsonPath("$[0].content").value("Mua sữa"))
                .andExpect(jsonPath("$[0].locationId").value(locationId.toString()))
                .andExpect(jsonPath("$[0].name").value("Siêu thị"))
                .andExpect(jsonPath("$[0].latitude").value(10.7769))
                .andExpect(jsonPath("$[0].longitude").value(106.7009))
                .andExpect(jsonPath("$[0].radius").value(200))
                .andExpect(jsonPath("$[0].trigger").value("ON_LEAVE"));
    }

    @Test
    void locationReminders_skipArchivedProjects() throws Exception {
        RegisteredUser user = frozenUser();
        UUID projectId = readUuid(authPost(user.accessToken(), "/api/v1/projects", """
                {"name": "Lưu trữ"}
                """).andExpect(status().isCreated()), "$.id");
        UUID taskId = task(user, """
                {"content": "Trong dự án", "projectId": "%s"}
                """.formatted(projectId));
        postReminder(user, taskId, locationReminder(location(user, "Nhà"), "ON_ENTER"))
                .andExpect(status().isCreated());
        authPost(user.accessToken(), "/api/v1/projects/" + projectId + "/archive").andExpect(status().isOk());

        authGet(user.accessToken(), "/api/v1/tasks/location-reminders")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deletingTheLocation_removesItsReminders() throws Exception {
        RegisteredUser user = frozenUser();
        UUID taskId = plainTask(user, "Việc");
        UUID locationId = location(user, "Nhà");
        postReminder(user, taskId, locationReminder(locationId, "ON_ENTER")).andExpect(status().isCreated());

        authDelete(user.accessToken(), "/api/v1/locations/" + locationId).andExpect(status().isNoContent());

        authGet(user.accessToken(), reminders(taskId)).andExpect(jsonPath("$.length()").value(0));
        authGet(user.accessToken(), "/api/v1/tasks/location-reminders").andExpect(jsonPath("$.length()").value(0));
    }
}
