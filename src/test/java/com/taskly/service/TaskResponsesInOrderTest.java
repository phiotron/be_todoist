package com.taskly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.dto.task.TaskResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** M2 dựng kết quả tìm kiếm / lọc từ danh sách id đã sắp ở SQL → phải giữ thứ tự, bỏ id lạ / đã xoá / của người khác. */
class TaskResponsesInOrderTest extends IntegrationTestBase {

    @Autowired
    private TaskViewService taskViewService;

    private UUID createTask(String token, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/tasks")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"" + content + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(JsonPath.read(body(result), "$.id"));
    }

    @Test
    void keepsRequestedOrder_skipsUnknownAndDeletedIds() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID a = createTask(user.accessToken(), "A");
        UUID b = createTask(user.accessToken(), "B");
        UUID c = createTask(user.accessToken(), "C");
        UUID gone = createTask(user.accessToken(), "Đã xoá");
        mockMvc.perform(delete("/api/v1/tasks/" + gone).header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isNoContent());

        List<TaskResponse> result =
                taskViewService.getResponsesInOrder(user.id(), List.of(c, UUID.randomUUID(), a, gone, b));

        assertThat(result).extracting(TaskResponse::content).containsExactly("C", "A", "B");
        assertThat(result).extracting(TaskResponse::projectName).containsOnly("Inbox");
        assertThat(taskViewService.getResponsesInOrder(user.id(), List.of())).isEmpty();
    }

    @Test
    void otherUsersTasks_areSkipped() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID aliceTask = createTask(alice.accessToken(), "Của Alice");

        assertThat(taskViewService.getResponsesInOrder(bob.id(), List.of(aliceTask))).isEmpty();
    }
}
