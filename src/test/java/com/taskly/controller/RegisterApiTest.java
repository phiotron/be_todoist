package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.security.OpaqueTokens;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class RegisterApiTest extends IntegrationTestBase {

    private static MockHttpServletRequestBuilder register(String json) {
        return post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(json);
    }

    @Test
    void register_returns201WithTokensAndNormalizedUser() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(register("""
                        {"email": "  %s  ", "password": "%s", "displayName": "  Nguyễn Văn A  "}
                        """.formatted(email.toUpperCase(), FIXTURE_PASSWORD)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken", matchesPattern("[A-Za-z0-9_-]{43}")))
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(900))
                .andExpect(jsonPath("$.user.id").isString())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.displayName").value("Nguyễn Văn A"))
                .andExpect(jsonPath("$.user.locale").value("vi"))
                .andExpect(jsonPath("$.user.timeZone").value("Asia/Ho_Chi_Minh"))
                .andExpect(content().string(not(containsString("password"))));
    }

    @Test
    void register_createsPersonalWorkspaceAdminMembershipInboxAndHashedSecrets() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        Map<String, Object> workspace = jdbcTemplate.queryForMap(
                "select id, name, type from workspaces where owner_id = ?", user.id());
        assertThat(workspace).containsEntry("name", "Cá nhân").containsEntry("type", "PERSONAL");

        List<Map<String, Object>> members = jdbcTemplate.queryForList(
                "select user_id, role from workspace_members where workspace_id = ?", workspace.get("id"));
        assertThat(members).hasSize(1);
        assertThat(members.get(0)).containsEntry("user_id", user.id()).containsEntry("role", "ADMIN");

        List<Map<String, Object>> projects = jdbcTemplate.queryForList(
                "select name, is_inbox, view_style, sort_order from projects where workspace_id = ?",
                workspace.get("id"));
        assertThat(projects).hasSize(1);
        assertThat(projects.get(0)).containsEntry("name", "Inbox")
                .containsEntry("is_inbox", true)
                .containsEntry("view_style", "LIST")
                .containsEntry("sort_order", 0);

        String passwordHash = jdbcTemplate.queryForObject(
                "select password_hash from users where id = ?", String.class, user.id());
        assertThat(passwordHash).startsWith("$2").isNotEqualTo(FIXTURE_PASSWORD);

        Integer storedTokens = jdbcTemplate.queryForObject(
                "select count(*) from refresh_tokens where user_id = ? and token_hash = ?",
                Integer.class, user.id(), OpaqueTokens.sha256Hex(user.refreshToken()));
        assertThat(storedTokens).isEqualTo(1);
    }

    @Test
    void register_duplicateEmailIgnoringCase_returns409ProblemDetail() throws Exception {
        String email = uniqueEmail();
        registerUser(email);

        mockMvc.perform(register("""
                        {"email": " %s ", "password": "%s", "displayName": "Trùng"}
                        """.formatted(email.toUpperCase(), FIXTURE_PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Xung đột dữ liệu"))
                .andExpect(jsonPath("$.detail").value("Email đã được dùng"))
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/register"));
    }

    @Test
    void register_invalidFields_returns400WithErrors() throws Exception {
        mockMvc.perform(register("""
                        {"email": "not-an-email", "password": "short", "displayName": "   "}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Dữ liệu không hợp lệ"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("email", "password", "displayName")))
                // App mobile hiển thị nguyên văn message → phải đúng câu tiếng Việt
                .andExpect(jsonPath("$.errors[?(@.field == 'email')].message", contains("Email không hợp lệ")))
                .andExpect(jsonPath("$.errors[?(@.field == 'password')].message",
                        contains("Mật khẩu phải từ 8 đến 72 ký tự")))
                .andExpect(jsonPath("$.errors[?(@.field == 'displayName')].message",
                        contains("Vui lòng nhập tên hiển thị")));
    }

    @Test
    void register_missingFields_returns400WithOneErrorPerField() throws Exception {
        mockMvc.perform(register("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("email", "password", "displayName")))
                .andExpect(jsonPath("$.errors[*].message", containsInAnyOrder(
                        "Vui lòng nhập email", "Vui lòng nhập mật khẩu", "Vui lòng nhập tên hiển thị")));
    }

    @Test
    void register_tooLongEmailOrPasswordOverBcryptBytes_returns400() throws Exception {
        // Hợp lệ cú pháp @Email (local-part 64, mỗi nhãn miền <= 63) nhưng dài 64+1+3*64+3 = 260 > 254
        // → chỉ có thể bị chặn bởi @Size, không phải @Email.
        String longEmail = "a".repeat(64) + "@" + ("b".repeat(63) + ".").repeat(3) + "com";
        assertThat(longEmail.length()).isGreaterThan(254);
        mockMvc.perform(register("""
                        {"email": "%s", "password": "%s", "displayName": "A"}
                        """.formatted(longEmail, FIXTURE_PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')].message", contains("Email tối đa 254 ký tự")));

        String vietnamesePassword = "ạ".repeat(30); // 30 ký tự (hợp lệ theo số ký tự) nhưng 90 byte UTF-8 > 72
        mockMvc.perform(register("""
                        {"email": "%s", "password": "%s", "displayName": "A"}
                        """.formatted(uniqueEmail(), vietnamesePassword)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field", hasItem("password")));
    }

    @Test
    void register_malformedJson_returns400ProblemDetail() throws Exception {
        mockMvc.perform(register("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Dữ liệu không hợp lệ"))
                .andExpect(jsonPath("$.detail").value("Dữ liệu gửi lên không đọc được hoặc không hợp lệ"));
    }

    @Test
    void register_ignoresStaleBearerHeader() throws Exception {
        mockMvc.perform(register("""
                        {"email": "%s", "password": "%s", "displayName": "A"}
                        """.formatted(uniqueEmail(), FIXTURE_PASSWORD))
                        .header(HttpHeaders.AUTHORIZATION, bearer("expired-or-garbage")))
                .andExpect(status().isCreated());
    }
}
