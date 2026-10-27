package com.taskly.controller;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.GoogleIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** ProblemDetail của M5 theo Accept-Language (M3): en khi xin en, mặc định tiếng Việt; property riêng còn nguyên. */
class GoogleI18nApiTest extends GoogleIntegrationTestBase {

    @Test
    void englishAcceptLanguage_translatesGoogleErrors() throws Exception {
        mockMvc.perform(googleSignInRequest("not-a-jwt").header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Google sign-in failed, please try again"));

        RegisteredUser existing = registerUser(uniqueEmail());
        mockMvc.perform(googleSignInRequest(googleTokens.idToken().email(existing.email()).sign())
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        "This email already has a Taskly account. Sign in with your password to link Google"))
                .andExpect(jsonPath("$.code").value("link-required"))
                .andExpect(jsonPath("$.linkToken").isString());

        mockMvc.perform(post(GOOGLE_SIGN_IN).with(randomRemoteAddress())
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "en")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'idToken')].message", contains("idToken is required")));
    }

    @Test
    void defaultLanguageStaysVietnamese() throws Exception {
        postGoogleSignIn("not-a-jwt")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Đăng nhập Google không thành công, vui lòng thử lại"));
    }
}
