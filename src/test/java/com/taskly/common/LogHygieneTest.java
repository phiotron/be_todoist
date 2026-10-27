package com.taskly.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class LogHygieneTest extends IntegrationTestBase {

    @Test
    void logsNeverContainTaskContentPasswordOrTokens(CapturedOutput output) throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        String secret = "NOI-DUNG-BI-MAT-" + UUID.randomUUID();
        UUID inbox = testData.inboxOf(user.id());

        UUID id = readUuid(authPost(user.accessToken(), "/api/v1/tasks",
                "{\"projectId\": \"%s\", \"content\": \"%s\"}".formatted(inbox, secret))
                .andExpect(status().isCreated()), "$.id");
        authPatch(user.accessToken(), "/api/v1/tasks/" + id,
                "{\"version\": 0, \"description\": \"%s\"}".formatted(secret)).andExpect(status().isOk());
        authPatch(user.accessToken(), "/api/v1/tasks/" + id,
                "{\"version\": 0, \"content\": \"%s\"}".formatted(secret)).andExpect(status().isConflict());
        authPost(user.accessToken(), "/api/v1/tasks", "{\"projectId\": \"%s\", \"content\": \"%s\"}"
                .formatted(inbox, secret + "x".repeat(500))).andExpect(status().isBadRequest());
        authPost(user.accessToken(), "/api/v1/tasks/batch", "{\"action\": \"PRIORITY\", \"priority\": 2, \"taskIds\": [\"%s\"]}"
                .formatted(id)).andExpect(status().isOk());
        authDelete(user.accessToken(), "/api/v1/tasks/" + id).andExpect(status().isNoContent());
        postJson("/api/v1/auth/login", "{\"email\": \"%s\", \"password\": \"%s\"}"
                .formatted(user.email(), FIXTURE_PASSWORD)).andExpect(status().isOk());
        postJson("/api/v1/auth/refresh", "{\"refreshToken\": \"%s\"}".formatted(user.refreshToken()))
                .andExpect(status().isOk());

        assertThat(output.getAll())
                .doesNotContain(secret)
                .doesNotContain(FIXTURE_PASSWORD)
                .doesNotContain(user.refreshToken())
                .doesNotContain(user.accessToken());
    }
}
