package com.taskly.common.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import com.taskly.security.JwtTokenIssuer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** ProblemDetail dịch theo Accept-Language (vi mặc định, en khi yêu cầu), kể cả 401 phát sinh ở filter security. */
class ProblemDetailLocalizationTest extends IntegrationTestBase {

    @Autowired
    private JwtTokenIssuer jwtTokenIssuer;

    @Test
    void withoutAcceptLanguage_problemIsVietnamese() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Chưa xác thực"))
                .andExpect(jsonPath("$.detail").value("Phiên đăng nhập không hợp lệ hoặc đã hết hạn"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "en-US,en;q=0.9", "en-GB"})
    void englishAcceptLanguage_problemIsEnglish(String acceptLanguage) throws Exception {
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthenticated"))
                .andExpect(jsonPath("$.detail").value("Your session is invalid or has expired"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"vi", "vi-VN", "fr", "fr-FR,de;q=0.8", "ja"})
    void vietnameseOrUnsupportedLanguage_problemIsVietnamese(String acceptLanguage) throws Exception {
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Chưa xác thực"))
                .andExpect(jsonPath("$.detail").value("Phiên đăng nhập không hợp lệ hoặc đã hết hạn"));
    }

    @Test
    void businessErrorDetail_isTranslated() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .content("""
                                {"email": "%s", "password": "Wrong-Pass-999"}
                                """.formatted(user.email())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthenticated"))
                .andExpect(jsonPath("$.detail").value("Incorrect email or password"));
    }

    @Test
    void conflictTitleAndDetail_areTranslated() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .content("""
                                {"email": "%s", "password": "%s", "displayName": "Trùng"}
                                """.formatted(user.email(), FIXTURE_PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("This email is already in use"));
    }

    @Test
    void frameworkErrors_useTranslatedDefaultDetail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid data"))
                .andExpect(jsonPath("$.detail").value("The request body could not be read or is invalid"));

        String token = jwtTokenIssuer.issueAccessToken(UUID.randomUUID());
        mockMvc.perform(get("/api/v1/khong-ton-tai").header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.detail").value("The requested path was not found"));
    }

    @Test
    void validationErrorDetail_isTranslated() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid data"))
                .andExpect(jsonPath("$.detail").value("The submitted data is invalid"));
    }
}
