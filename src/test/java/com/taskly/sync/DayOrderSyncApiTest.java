package com.taskly.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** DND: lệnh sync `reorder` (args = body REST) nhận type TASK_DAY — kéo-thả trong Today chạy được khi offline. */
class DayOrderSyncApiTest extends SyncTestBase {

    @Test
    void reorderCommand_withTaskDay_ordersTheDay() throws Exception {
        clock.freezeAt(Instant.parse("2026-10-23T03:00:00Z")); // 10:00 ngày 23 giờ Việt Nam
        String token = registerUser(uniqueEmail()).accessToken();
        UUID a = createTask(token, "{\"content\": \"a\", \"dueDate\": \"2026-10-23\"}");
        UUID b = createTask(token, "{\"content\": \"b\", \"dueDate\": \"2026-10-23\"}");

        String body = syncOk(token, request("*",
                cmd("reorder", "{\"type\": \"TASK_DAY\", \"id\": \"" + b + "\", \"afterId\": null}")));

        assertThat(okStatuses(body)).isEqualTo("ok");
        List<String> today = JsonPath.read(getOk(token, "/api/v1/tasks?view=today"), "$.items[*].content");
        assertThat(today).containsExactly("b", "a");
        assertThat(a).isNotNull();
    }
}
