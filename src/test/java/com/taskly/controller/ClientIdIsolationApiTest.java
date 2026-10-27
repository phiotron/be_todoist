package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** spec 12.11 / C8: `id` do client sinh mà thuộc NGƯỜI KHÁC → 409 id-conflict (cùng câu trả lời với "khác nội dung"). */
class ClientIdIsolationApiTest extends IntegrationTestBase {

    @Test
    void anotherUsersClientId_is409IdConflict_forProjectsSectionsAndTasks_andNothingChanges() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID projectId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        authPost(alice.accessToken(), "/api/v1/projects", "{\"id\": \"%s\", \"name\": \"Của Alice\"}".formatted(projectId))
                .andExpect(status().isCreated());
        authPost(alice.accessToken(), "/api/v1/projects/" + projectId + "/sections",
                "{\"id\": \"%s\", \"name\": \"S\"}".formatted(sectionId)).andExpect(status().isCreated());
        authPost(alice.accessToken(), "/api/v1/tasks",
                "{\"id\": \"%s\", \"projectId\": \"%s\", \"content\": \"T\"}".formatted(taskId, projectId))
                .andExpect(status().isCreated());
        UUID bobProject = testData.project(bob.id(), "Của Bob");

        authPost(bob.accessToken(), "/api/v1/projects", "{\"id\": \"%s\", \"name\": \"Của Alice\"}".formatted(projectId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("id-conflict"));
        authPost(bob.accessToken(), "/api/v1/projects/" + bobProject + "/sections",
                "{\"id\": \"%s\", \"name\": \"S\"}".formatted(sectionId))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("id-conflict"));
        authPost(bob.accessToken(), "/api/v1/tasks",
                "{\"id\": \"%s\", \"projectId\": \"%s\", \"content\": \"T\"}".formatted(taskId, bobProject))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("id-conflict"));

        assertThat(jdbcTemplate.queryForObject("select count(*) from tasks where id = ? and project_id = ?",
                Integer.class, taskId, projectId)).isOne();
        assertThat(jdbcTemplate.queryForObject("select count(*) from projects where id = ?", Integer.class, projectId)).isOne();
        authPost(alice.accessToken(), "/api/v1/tasks", "{\"id\": \"khong-phai-uuid\", \"content\": \"x\"}")
                .andExpect(status().isBadRequest()); // id không phải UUID → 400
    }
}
