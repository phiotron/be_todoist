package com.taskly.sync;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.support.M2ApiTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.test.web.servlet.ResultActions;

/** Helper cho test POST /api/v1/sync (M9): dựng lệnh dạng JSON, gọi sync, đọc kết quả. */
abstract class SyncTestBase extends M2ApiTestBase {

    static final String SYNC = "/api/v1/sync";

    /** Một lệnh; tempId null = không gửi; args là JSON object. */
    static String cmd(UUID uuid, String type, String tempId, String args) {
        return "{\"uuid\": \"" + uuid + "\", \"type\": \"" + type + "\""
                + (tempId == null ? "" : ", \"tempId\": \"" + tempId + "\"")
                + ", \"args\": " + args + "}";
    }

    static String cmd(String type, String args) {
        return cmd(UUID.randomUUID(), type, null, args);
    }

    /** Body {syncToken, commands}; token null = gửi null. */
    static String request(String token, String... commands) {
        return "{\"syncToken\": " + (token == null ? "null" : "\"" + token + "\"")
                + ", \"commands\": [" + String.join(", ", commands) + "]}";
    }

    ResultActions sync(String accessToken, String json) throws Exception {
        return apiPost(accessToken, SYNC, json);
    }

    /** 200 rồi trả body. */
    String syncOk(String accessToken, String json) throws Exception {
        return okBody(sync(accessToken, json));
    }

    /** Token hiện tại (không lấy thay đổi). */
    String freshToken(String accessToken) throws Exception {
        String body = syncOk(accessToken, "{\"resourceTypes\": []}");
        return JsonPath.read(body, "$.syncToken");
    }

    String okStatuses(String body) {
        List<String> statuses = JsonPath.read(body, "$.results[*].status");
        return String.join(",", statuses);
    }

    static List<String> idsOf(String body, String key) {
        return new ArrayList<>(JsonPath.<List<String>>read(body, "$.changes." + key + "[*].id"));
    }

    static UUID idOf(ResultActions created) throws Exception {
        return UUID.fromString(JsonPath.read(body(created.andExpect(status().isCreated()).andReturn()), "$.id"));
    }

    UUID createTask(String accessToken, String json) throws Exception {
        return idOf(apiPost(accessToken, "/api/v1/tasks", json));
    }

    UUID createProject(String accessToken, String name) throws Exception {
        return idOf(apiPost(accessToken, "/api/v1/projects", "{\"name\": \"" + name + "\"}"));
    }

    UUID createSection(String accessToken, UUID projectId, String name) throws Exception {
        return idOf(apiPost(accessToken, "/api/v1/projects/" + projectId + "/sections", "{\"name\": \"" + name + "\"}"));
    }
}
