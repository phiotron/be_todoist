package com.taskly.controller;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.GoogleIntegrationTestBase;
import org.junit.jupiter.api.Test;

/** Spec bản 3 mục 12.10: lỗi app cần phân biệt có "code"; dòng ghi "—" không có code. */
class GoogleErrorCodesApiTest extends GoogleIntegrationTestBase {

    @Test
    void unlinkWithoutPassword_returns409WithPasswordRequiredCode() throws Exception {
        GoogleUser user = signInWithGoogle(googleTokens.idToken());

        deleteGoogleLink(user.accessToken())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("password-required"))
                .andExpect(jsonPath("$.detail").value("Hãy đặt mật khẩu trước khi bỏ liên kết Google"));
    }

    @Test
    void linkRequiredKeepsItsCode() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        postGoogleSignIn(googleTokens.idToken().email(user.email()).sign())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("link-required"))
                .andExpect(jsonPath("$.linkToken").isString());
    }

    @Test
    void linkConflictsWithoutDistinctCase_haveNoCode() throws Exception {
        GoogleUser owner = signInWithGoogle(googleTokens.idToken());
        RegisteredUser other = registerUser(uniqueEmail());

        // sub đã gắn tài khoản khác
        postLinkGoogle(other.accessToken(), idTokenJson(googleTokens.idToken().subject(owner.subject()).sign()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").doesNotExist());
        // tài khoản đã gắn một Google khác
        postLinkGoogle(other.accessToken(), idTokenJson(googleTokens.idToken().sign()))
                .andExpect(status().isNoContent());
        postLinkGoogle(other.accessToken(), idTokenJson(googleTokens.idToken().sign()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    @Test
    void otherGoogleErrors_haveNoCode() throws Exception {
        postGoogleSignIn("not-a-jwt")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").doesNotExist());
        postGoogleSignIn(googleTokens.idToken().emailVerified(false).sign())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").doesNotExist());

        RegisteredUser user = registerUser(uniqueEmail());
        postLinkGoogle(user.accessToken(), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").doesNotExist());
        postLinkGoogle(user.accessToken(), linkTokenJson("not-a-link-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").doesNotExist());
        deleteGoogleLink(user.accessToken())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").doesNotExist());
    }
}
