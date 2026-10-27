package com.taskly.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** /v3/api-docs là nguồn để mobile sinh types.gen.ts (npm run gen:api) — phải công khai và đúng hợp đồng. */
class OpenApiDocsTest extends IntegrationTestBase {

    @Test
    void apiDocs_isPublicAndListsAllM0Endpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me'].get").exists());
    }

    @Test
    void apiDocs_declaresBearerSchemeOnProtectedEndpoints() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/v1/me'].get.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/logout'].post.security[0].bearerAuth").exists());
    }

    @Test
    void apiDocs_schemasMatchContractAndHideSecrets() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                // tham số @AuthenticationPrincipal Jwt không được lộ thành query param
                .andExpect(jsonPath("$.paths['/api/v1/me'].get.parameters").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.id").exists())
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.timeZone").exists())
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.AuthResponse.properties.accessTokenExpiresIn.type")
                        .value("integer"));
    }

    @Test
    void apiDocs_requiredFieldsMatchContractForTypeGeneration() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                // 6 schema DTO tồn tại (openapi-typescript sinh type theo tên)
                .andExpect(jsonPath("$.components.schemas.RegisterRequest").exists())
                .andExpect(jsonPath("$.components.schemas.LoginRequest").exists())
                .andExpect(jsonPath("$.components.schemas.RefreshRequest").exists())
                .andExpect(jsonPath("$.components.schemas.LogoutRequest").exists())
                .andExpect(jsonPath("$.components.schemas.AuthResponse").exists())
                .andExpect(jsonPath("$.components.schemas.UserResponse").exists())
                // response: mọi field bắt buộc, để mobile không phải bọc kiểu optional
                .andExpect(jsonPath("$.components.schemas.AuthResponse.required")
                        .value(containsInAnyOrder("accessToken", "refreshToken", "accessTokenExpiresIn", "user")))
                .andExpect(jsonPath("$.components.schemas.UserResponse.required")
                        .value(containsInAnyOrder("id", "email", "displayName", "locale", "timeZone", "twoFactorEnabled")))
                // request: field bắt buộc lấy từ Bean Validation
                .andExpect(jsonPath("$.components.schemas.RegisterRequest.required")
                        .value(containsInAnyOrder("email", "password", "displayName")))
                .andExpect(jsonPath("$.components.schemas.LoginRequest.required")
                        .value(containsInAnyOrder("email", "password")))
                .andExpect(jsonPath("$.components.schemas.RefreshRequest.required")
                        .value(containsInAnyOrder("refreshToken")))
                .andExpect(jsonPath("$.components.schemas.LogoutRequest.required")
                        .value(containsInAnyOrder("refreshToken")));
    }

    @Test
    void apiDocs_publicAuthEndpointsDeclareNoSecurity() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post.security").doesNotExist())
                // không áp security toàn cục: chỉ endpoint cần Bearer mới khai báo
                .andExpect(jsonPath("$.security").doesNotExist());
    }

    @Test
    void swaggerUi_isPublic() throws Exception {
        // Trang Swagger UI trả 200 ổn định trong MockMvc; 401/403 nghĩa là SecurityConfig chặn nhầm.
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
