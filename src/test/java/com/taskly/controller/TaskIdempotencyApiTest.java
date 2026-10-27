package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class TaskIdempotencyApiTest extends IntegrationTestBase {

    private int countByContent(UUID projectId, String content) {
        return jdbcTemplate.queryForObject("select count(*) from tasks where project_id = ? and content = ?",
                Integer.class, projectId, content);
    }

    @Test
    void createTaskTwiceWithSameKey_createsOneTask() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        String key = UUID.randomUUID().toString();
        String json = "{\"projectId\": \"%s\", \"content\": \"Bấm hai lần\"}".formatted(inbox);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/tasks").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                            .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isCreated());
        }
        assertThat(countByContent(inbox, "Bấm hai lần")).isOne();
    }

    @Test
    void duplicateTwiceWithSameKey_createsOneCopy() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        UUID task = testData.task(user.id(), inbox, "Sao một lần");
        String key = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/v1/tasks/" + task + "/duplicate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())).header("Idempotency-Key", key))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/tasks/" + task + "/duplicate")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())).header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"));

        assertThat(countByContent(inbox, "Sao một lần")).isEqualTo(2); // bản gốc + đúng một bản sao
    }

    private ResultActions postWithKey(RegisteredUser user, String uri, String json, String key) throws Exception {
        return mockMvc.perform(post(uri).header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken()))
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void batchDeleteWithSameKey_secondCallIsAReplay_notA404ForTheWholeBatch() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID task = testData.task(user.id(), testData.inboxOf(user.id()), "Xoá một lần");
        String json = "{\"action\": \"DELETE\", \"taskIds\": [\"%s\"]}".formatted(task);
        String key = UUID.randomUUID().toString();

        postWithKey(user, "/api/v1/tasks/batch", json, key)
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Idempotent-Replay"))
                .andExpect(jsonPath("$.affectedTaskIds[0]").value(task.toString()));
        postWithKey(user, "/api/v1/tasks/batch", json, key) // gửi lại sau timeout: cùng kết quả (spec 5.6, I6)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.affectedTaskIds[0]").value(task.toString()));
    }

    @Test
    void batchDeleteWithoutKey_secondCallIs404_becauseMoveAndDeleteAreNotIdempotentWithoutAKey() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID task = testData.task(user.id(), testData.inboxOf(user.id()), "Xoá không key");
        String json = "{\"action\": \"DELETE\", \"taskIds\": [\"%s\"]}".formatted(task);

        authPost(user.accessToken(), "/api/v1/tasks/batch", json).andExpect(status().isOk());
        authPost(user.accessToken(), "/api/v1/tasks/batch", json).andExpect(status().isNotFound());
    }

    @Test
    void batchWithSameKeyButDifferentBody_is422IdempotencyMismatch() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        UUID first = testData.task(user.id(), inbox, "a");
        UUID second = testData.task(user.id(), inbox, "b");
        String key = UUID.randomUUID().toString();

        postWithKey(user, "/api/v1/tasks/batch", "{\"action\": \"CLOSE\", \"taskIds\": [\"%s\"]}".formatted(first), key)
                .andExpect(status().isOk());
        postWithKey(user, "/api/v1/tasks/batch", "{\"action\": \"CLOSE\", \"taskIds\": [\"%s\"]}".formatted(second), key)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("idempotency-mismatch"));
    }

    @Test
    void rescheduleNeedsNoKey_andRepeatingItIsHarmless() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        authPost(user.accessToken(), "/api/v1/tasks/overdue/reschedule", "{\"date\": \"2030-01-01\"}")
                .andExpect(status().isOk());
        authPost(user.accessToken(), "/api/v1/tasks/overdue/reschedule", "{\"date\": \"2030-01-01\"}")
                .andExpect(status().isOk());
    }
}
