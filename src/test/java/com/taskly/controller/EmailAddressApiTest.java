package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.security.OpaqueTokens;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * M13a — địa chỉ nhận thư của dự án / Inbox / task: PUT = lấy hoặc tạo (ổn định), POST regenerate = địa chỉ mới, người
 * ngoài 404, DB chỉ giữ hash + bản mã (không có token rõ), xoá dự án / task xoá luôn địa chỉ.
 */
class EmailAddressApiTest extends IntegrationTestBase {

    static final String ADDRESS_PATTERN = "^[a-z2-7]{26}@in\\.taskly\\.test$";

    private String address(String token, String uri) throws Exception {
        return readString(authPut(token, uri).andExpect(status().isOk()), "$.address");
    }

    @Test
    void projectAddressIsCreatedOnce_thenReturnedUnchanged() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "Việc nhà");
        String uri = "/api/v1/projects/" + project + "/email-address";

        authPut(user.accessToken(), uri)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value(org.hamcrest.Matchers.matchesPattern(ADDRESS_PATTERN)))
                .andExpect(jsonPath("$.targetType").value("PROJECT"))
                .andExpect(jsonPath("$.targetId").value(project.toString()));
        String first = address(user.accessToken(), uri);
        assertThat(address(user.accessToken(), uri)).isEqualTo(first);
        assertThat(jdbcTemplate.queryForObject("select count(*) from email_inboxes where project_id = ?", Integer.class,
                project)).isEqualTo(1);
    }

    @Test
    void storesOnlyHashAndCiphertext_neverThePlainToken() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        String token = address(user.accessToken(), "/api/v1/projects/" + project + "/email-address").split("@")[0];

        var row = jdbcTemplate.queryForMap("select token_hash, token_cipher from email_inboxes where project_id = ?",
                project);
        assertThat(row.get("token_hash")).isEqualTo(OpaqueTokens.sha256Hex(token));
        assertThat((String) row.get("token_cipher")).doesNotContain(token).startsWith("v1:");
    }

    @Test
    void unreadableCiphertext_issuesAFreshAddress() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        String uri = "/api/v1/projects/" + project + "/email-address";
        String before = address(user.accessToken(), uri);
        // mô phỏng đổi TOTP_ENCRYPTION_KEY: bản mã cũ không giải được nữa
        jdbcTemplate.update("update email_inboxes set token_cipher = 'v1:AAAA' where project_id = ?", project);

        String after = address(user.accessToken(), uri);
        assertThat(after).matches(ADDRESS_PATTERN).isNotEqualTo(before);
        assertThat(address(user.accessToken(), uri)).isEqualTo(after);
    }

    @Test
    void inboxHasItsOwnAddress() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID inbox = testData.inboxOf(user.id());
        authPut(user.accessToken(), "/api/v1/projects/" + inbox + "/email-address")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetType").value("PROJECT"))
                .andExpect(jsonPath("$.targetId").value(inbox.toString()));
    }

    @Test
    void taskAddressDiffersFromItsProjectAddress() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID task = testData.task(user.id(), project, "T");

        String projectAddress = address(user.accessToken(), "/api/v1/projects/" + project + "/email-address");
        authPut(user.accessToken(), "/api/v1/tasks/" + task + "/email-address")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").value(org.hamcrest.Matchers.matchesPattern(ADDRESS_PATTERN)))
                .andExpect(jsonPath("$.targetType").value("TASK"))
                .andExpect(jsonPath("$.targetId").value(task.toString()));
        assertThat(address(user.accessToken(), "/api/v1/tasks/" + task + "/email-address")).isNotEqualTo(projectAddress);
    }

    @Test
    void regenerateReplacesTheAddress() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID task = testData.task(user.id(), project, "T");
        for (String base : new String[] {"/api/v1/projects/" + project, "/api/v1/tasks/" + task}) {
            String before = address(user.accessToken(), base + "/email-address");
            String regenerated = readString(authPost(user.accessToken(), base + "/email-address/regenerate")
                    .andExpect(status().isOk()), "$.address");
            assertThat(regenerated).matches(ADDRESS_PATTERN).isNotEqualTo(before);
            assertThat(address(user.accessToken(), base + "/email-address")).isEqualTo(regenerated);
        }
    }

    @Test
    void regenerateWithoutAnAddressYetCreatesOne() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        String created = readString(authPost(user.accessToken(), "/api/v1/projects/" + project
                + "/email-address/regenerate").andExpect(status().isOk()), "$.address");
        assertThat(address(user.accessToken(), "/api/v1/projects/" + project + "/email-address")).isEqualTo(created);
    }

    @Test
    void outsiderGets404_andNothingIsCreated() throws Exception {
        RegisteredUser alice = registerUser(uniqueEmail());
        RegisteredUser bob = registerUser(uniqueEmail());
        UUID project = testData.project(alice.id(), "Của Alice");
        UUID task = testData.task(alice.id(), project, "T");

        authPut(bob.accessToken(), "/api/v1/projects/" + project + "/email-address").andExpect(status().isNotFound());
        authPost(bob.accessToken(), "/api/v1/projects/" + project + "/email-address/regenerate")
                .andExpect(status().isNotFound());
        authPut(bob.accessToken(), "/api/v1/tasks/" + task + "/email-address").andExpect(status().isNotFound());
        authPost(bob.accessToken(), "/api/v1/tasks/" + task + "/email-address/regenerate")
                .andExpect(status().isNotFound());
        authPut(bob.accessToken(), "/api/v1/projects/" + UUID.randomUUID() + "/email-address")
                .andExpect(status().isNotFound());
        assertThat(jdbcTemplate.queryForObject("select count(*) from email_inboxes where user_id = ?", Integer.class,
                bob.id())).isZero();
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(put("/api/v1/projects/" + UUID.randomUUID() + "/email-address"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/tasks/" + UUID.randomUUID() + "/email-address/regenerate"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deletingTheProjectOrTaskDeletesItsAddress() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());
        UUID project = testData.project(user.id(), "P");
        UUID keep = testData.project(user.id(), "Q");
        UUID task = testData.task(user.id(), keep, "T");
        address(user.accessToken(), "/api/v1/projects/" + project + "/email-address");
        address(user.accessToken(), "/api/v1/tasks/" + task + "/email-address");

        authDelete(user.accessToken(), "/api/v1/projects/" + project).andExpect(status().isNoContent());
        authDelete(user.accessToken(), "/api/v1/tasks/" + task).andExpect(status().isNoContent());
        assertThat(jdbcTemplate.queryForObject("select count(*) from email_inboxes where user_id = ?", Integer.class,
                user.id())).isZero();
    }

    @Test
    void apiDocsListTheNewEndpoints() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/email-address'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/projects/{projectId}/email-address/regenerate'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}/email-address'].put").exists())
                .andExpect(jsonPath("$.paths['/api/v1/tasks/{taskId}/email-address/regenerate'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/integrations/email/inbound'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/integrations/email/inbound'].post.security").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.EmailAddressResponse.required").value(
                        org.hamcrest.Matchers.containsInAnyOrder("address", "targetType", "targetId")));
    }
}
