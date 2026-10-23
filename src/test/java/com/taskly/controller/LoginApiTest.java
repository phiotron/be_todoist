package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import com.taskly.security.OpaqueTokens;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class LoginApiTest extends IntegrationTestBase {

    private static MockHttpServletRequestBuilder login(String email, String password) {
        return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email": "%s", "password": "%s"}
                """.formatted(email, password));
    }

    @Test
    void login_withCorrectCredentials_returns200AndUsableTokens() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        MvcResult result = mockMvc.perform(login(user.email(), FIXTURE_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessTokenExpiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken", matchesPattern("[A-Za-z0-9_-]{43}")))
                .andExpect(jsonPath("$.user.id").value(user.id().toString()))
                .andExpect(jsonPath("$.user.email").value(user.email()))
                .andReturn();
        String json = body(result);
        String accessToken = JsonPath.read(json, "$.accessToken");
        String refreshToken = JsonPath.read(json, "$.refreshToken");

        assertThat(refreshToken).isNotEqualTo(user.refreshToken());
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.id().toString()));
    }

    @Test
    void login_emailIsCaseAndWhitespaceInsensitive() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(login("  " + user.email().toUpperCase() + " ", FIXTURE_PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(user.email()));
    }

    @Test
    void login_wrongPasswordAndUnknownEmail_return401WithIdenticalResponse() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        MvcResult wrongPwd = mockMvc.perform(login(user.email(), "Wrong-Pass-999"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Email hoặc mật khẩu không đúng"))
                .andReturn();
        MvcResult unknown = mockMvc.perform(login(uniqueEmail(), FIXTURE_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Email hoặc mật khẩu không đúng"))
                .andReturn();

        // Không để lộ email có tồn tại hay không qua bất kỳ khác biệt nào của response
        assertThat(body(unknown)).isEqualTo(body(wrongPwd));
        assertThat(Set.copyOf(unknown.getResponse().getHeaderNames()))
                .isEqualTo(Set.copyOf(wrongPwd.getResponse().getHeaderNames()));
    }

    @Test
    void login_passwordOverBcryptByteLimit_returns401NotServerError() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(login(user.email(), "ạ".repeat(30))) // 90 byte UTF-8 > 72
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Email hoặc mật khẩu không đúng"));
    }

    @Test
    void login_eachCallIssuesNewRefreshTokenStoredAsSha256() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        String first = JsonPath.read(body(mockMvc.perform(login(user.email(), FIXTURE_PASSWORD))
                .andExpect(status().isOk()).andReturn()), "$.refreshToken");
        String second = JsonPath.read(body(mockMvc.perform(login(user.email(), FIXTURE_PASSWORD))
                .andExpect(status().isOk()).andReturn()), "$.refreshToken");

        assertThat(first).isNotEqualTo(second);
        for (String token : List.of(first, second)) {
            Integer stored = jdbcTemplate.queryForObject(
                    "select count(*) from refresh_tokens where user_id = ? and token_hash = ?",
                    Integer.class, user.id(), OpaqueTokens.sha256Hex(token));
            assertThat(stored).as("dòng refresh_tokens của token đăng nhập").isEqualTo(1);
        }
    }

    @Test
    void login_blankFields_returns400WithErrors() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "  "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("email", "password")));
    }
}
