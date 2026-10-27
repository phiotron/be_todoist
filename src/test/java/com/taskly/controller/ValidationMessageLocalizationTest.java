package com.taskly.controller;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** errors[].message theo Accept-Language (tiếng Việt khi không gửi header — các test M0 đã khoá câu tiếng Việt). */
class ValidationMessageLocalizationTest extends IntegrationTestBase {

    @Test
    void register_invalidFields_messagesInEnglish() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .content("""
                                {"email": "khong-phai-email", "password": "ngan", "displayName": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid data"))
                .andExpect(jsonPath("$.detail").value("The submitted data is invalid"))
                .andExpect(jsonPath("$.errors[?(@.field == 'email')].message", contains("Invalid email address")))
                .andExpect(jsonPath("$.errors[?(@.field == 'password')].message",
                        contains("Password must be 8 to 72 characters")))
                .andExpect(jsonPath("$.errors[?(@.field == 'displayName')].message",
                        contains("Please enter a display name")));
    }

    @Test
    void login_missingFields_messagesInEnglish() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')].message", contains("Please enter your email")))
                .andExpect(jsonPath("$.errors[?(@.field == 'password')].message",
                        contains("Please enter your password")));
    }

    @Test
    void refresh_blankToken_messageInVietnameseByDefault() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'refreshToken')].message",
                        contains("Thiếu refresh token")));
    }
}
