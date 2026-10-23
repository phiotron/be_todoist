package com.taskly.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.entity.User;
import com.taskly.repository.UserRepository;
import com.taskly.security.JwtTokenIssuer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class MeApiTest extends IntegrationTestBase {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenIssuer jwtTokenIssuer;

    @Test
    void me_withValidToken_returnsProfileWithoutPassword() throws Exception {
        User user = userRepository.saveAndFlush(new User(uniqueEmail(), "hash-not-used", "Người thử"));
        String token = jwtTokenIssuer.issueAccessToken(user.getId());

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.email").value(user.getEmail()))
                .andExpect(jsonPath("$.displayName").value("Người thử"))
                .andExpect(jsonPath("$.locale").value("vi"))
                .andExpect(jsonPath("$.timeZone").value("Asia/Ho_Chi_Minh"))
                .andExpect(content().string(not(containsString("password"))));
    }

    @Test
    void me_tokenOfUnknownUser_returns401ProblemDetail() throws Exception {
        String token = jwtTokenIssuer.issueAccessToken(UUID.randomUUID());

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Phiên đăng nhập không hợp lệ hoặc đã hết hạn"))
                .andExpect(jsonPath("$.instance").value("/api/v1/me"));
    }
}
