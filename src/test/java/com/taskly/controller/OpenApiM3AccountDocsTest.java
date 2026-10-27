package com.taskly.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** Hợp đồng OpenAPI của phần tài khoản spec bản 3: 2FA, đăng nhập hai bước, đăng xuất mọi thiết bị, xoá tài khoản. */
class OpenApiM3AccountDocsTest extends IntegrationTestBase {

    @Test
    void apiDocs_listsSpecV3AccountEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/me/2fa'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/2fa/setup'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/2fa/enable'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/2fa/disable'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/2fa/recovery-codes'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/2fa/verify'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/logout-all'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me'].delete").exists());
    }

    @Test
    void apiDocs_accountEndpointsNeedBearer_twoFactorVerifyDoesNot() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/me/2fa'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/2fa/setup'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/2fa/enable'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/2fa/disable'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/2fa/recovery-codes'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/logout-all'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me'].delete.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/2fa/verify'].post.security").doesNotExist());
    }

    @Test
    void apiDocs_responseCodes() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/me/2fa/setup'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/2fa/enable'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/2fa/disable'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/2fa/recovery-codes'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/2fa/verify'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/logout-all'].post.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me'].delete.responses['204']").exists());
    }

    @Test
    void apiDocs_schemasMatchContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.TwoFactorStatusResponse.required")
                        .value(containsInAnyOrder("enabled", "recoveryCodesLeft")))
                .andExpect(jsonPath("$.components.schemas.TwoFactorSetupResponse.required")
                        .value(containsInAnyOrder("secret", "otpauthUri")))
                .andExpect(jsonPath("$.components.schemas.RecoveryCodesResponse.required")
                        .value(containsInAnyOrder("recoveryCodes")))
                .andExpect(jsonPath("$.components.schemas.TwoFactorCodeRequest.required")
                        .value(containsInAnyOrder("code")))
                .andExpect(jsonPath("$.components.schemas.DisableTwoFactorRequest.required")
                        .value(containsInAnyOrder("code")))
                .andExpect(jsonPath("$.components.schemas.TwoFactorVerifyRequest.required")
                        .value(containsInAnyOrder("twoFactorToken", "code")))
                .andExpect(jsonPath("$.components.schemas.DeleteAccountRequest.required").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.UserResponse.required")
                        .value(containsInAnyOrder("id", "email", "displayName", "locale", "timeZone",
                                "twoFactorEnabled", "hasPassword", "linkedProviders")));
    }
}
