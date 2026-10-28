package com.taskly.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.common.error.ApiException;
import com.taskly.service.TaskService;
import com.taskly.service.sync.SyncCommandContext;
import com.taskly.service.sync.SyncCommandHandler;
import com.taskly.service.sync.handler.TaskSyncCommands;
import jakarta.validation.Validator;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Xung đột (M9 plan F4): mặc định ghi sau thắng theo từng trường; policy `version` → 409 sync-conflict. */
class SyncConflictApiTest extends SyncTestBase {

    @Autowired
    private TaskService taskService;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private Validator validator;

    @Test
    void fieldLastWriterWins_otherDevicesChangesOnOtherFieldsSurvive() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID taskId = createTask(token, "{\"content\": \"gốc\", \"priority\": 4}");
        // máy A (online) đổi ưu tiên → version tăng; máy B (offline từ lúc version 0) đổi tên
        apiPatch(token, "/api/v1/tasks/" + taskId, "{\"version\": 0, \"priority\": 1}").andExpect(status().isOk());

        String body = syncOk(token, request("*", cmd("task_update", "{\"id\": \"" + taskId + "\", \"content\": \"tên của B\"}")));

        assertThat(okStatuses(body)).isEqualTo("ok");
        apiGet(token, "/api/v1/tasks/" + taskId).andExpect(jsonPath("$.content").value("tên của B"))
                .andExpect(jsonPath("$.priority").value(1)); // thay đổi của A giữ nguyên
    }

    @Test
    void sameFieldTwice_theLaterArrivalWins() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID taskId = createTask(token, "{\"content\": \"gốc\"}");

        syncOk(token, request("*", cmd("task_update", "{\"id\": \"" + taskId + "\", \"content\": \"A\"}")));
        syncOk(token, request("*", cmd("task_update", "{\"id\": \"" + taskId + "\", \"content\": \"B\"}")));

        apiGet(token, "/api/v1/tasks/" + taskId).andExpect(jsonPath("$.content").value("B"));
    }

    @Test
    void updateOfDeletedRecord_isReportedAs404_deleteWins() throws Exception {
        String token = registerUser(uniqueEmail()).accessToken();
        UUID taskId = createTask(token, "{\"content\": \"sẽ bị xoá\"}");
        apiDelete(token, "/api/v1/tasks/" + taskId).andExpect(status().isNoContent());

        String body = syncOk(token, request("*", cmd("task_update", "{\"id\": \"" + taskId + "\", \"content\": \"x\"}")));

        assertThat(okStatuses(body)).isEqualTo("error");
        assertThat((Integer) com.jayway.jsonpath.JsonPath.read(body, "$.results[0].error.status")).isEqualTo(404);
    }

    @Test
    void versionPolicy_staleVersionIsSyncConflict_currentVersionPasses() throws Exception {
        var user = registerUser(uniqueEmail());
        UUID taskId = createTask(user.accessToken(), "{\"content\": \"gốc\"}");
        apiPatch(user.accessToken(), "/api/v1/tasks/" + taskId, "{\"version\": 0, \"priority\": 1}").andExpect(status().isOk());
        SyncCommandHandler handler = new TaskSyncCommands().taskUpdateCommand(taskService, TaskSyncCommands.POLICY_VERSION);

        ObjectNode stale = (ObjectNode) objectMapper.readTree("{\"id\": \"" + taskId + "\", \"version\": 0, \"content\": \"B\"}");
        assertThatThrownBy(() -> handler.handle(new SyncCommandContext(user.id(), stale, objectMapper, validator)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getBody().getStatus()).isEqualTo(409);
                    assertThat(e.getBody().getProperties()).containsEntry("code", "sync-conflict").containsKey("current");
                });

        ObjectNode fresh = (ObjectNode) objectMapper.readTree("{\"id\": \"" + taskId + "\", \"version\": 1, \"content\": \"B\"}");
        handler.handle(new SyncCommandContext(user.id(), fresh, objectMapper, validator));
        apiGet(user.accessToken(), "/api/v1/tasks/" + taskId).andExpect(jsonPath("$.content").value("B"));
    }
}
