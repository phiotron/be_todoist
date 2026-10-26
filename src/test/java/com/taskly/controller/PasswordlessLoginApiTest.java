package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.User;
import com.taskly.repository.UserRepository;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** spec 12.1: auth/login vào tài khoản không có mật khẩu (chỉ Google) → 401 GIỐNG HỆT sai mật khẩu (chống dò). */
class PasswordlessLoginApiTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    private static MockHttpServletRequestBuilder login(String email, String password) {
        return post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"email": "%s", "password": "%s"}
                """.formatted(email, password));
    }

    @Test
    void googleOnlyAccount_passwordLoginReturnsSame401AsUnknownEmail() throws Exception {
        String email = uniqueEmail();
        userRepository.saveAndFlush(new User(email, null, "Chỉ có Google"));

        MvcResult passwordless = mockMvc.perform(login(email, FIXTURE_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Email hoặc mật khẩu không đúng"))
                .andReturn();
        MvcResult unknown = mockMvc.perform(login(uniqueEmail(), FIXTURE_PASSWORD))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(body(passwordless)).isEqualTo(body(unknown));
        assertThat(Set.copyOf(passwordless.getResponse().getHeaderNames()))
                .isEqualTo(Set.copyOf(unknown.getResponse().getHeaderNames()));
    }

    @Test
    void googleOnlyAccount_emptyPasswordAlsoRejected() throws Exception {
        String email = uniqueEmail();
        userRepository.saveAndFlush(new User(email, null, "Chỉ có Google"));

        mockMvc.perform(login(email, ""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Email hoặc mật khẩu không đúng"));
    }
}
