package com.taskly.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** Hợp đồng OpenAPI: ba field mật khẩu mới vẫn công bố minLength 8 / maxLength 72 dù dùng constraint ghép @ValidPassword. */
class OpenApiPasswordSchemaTest extends IntegrationTestBase {

    @Test
    void newPasswordFields_exposeLengthBounds() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.RegisterRequest.properties.password.minLength").value(8))
                .andExpect(jsonPath("$.components.schemas.RegisterRequest.properties.password.maxLength").value(72))
                .andExpect(jsonPath("$.components.schemas.ChangePasswordRequest.properties.newPassword.minLength").value(8))
                .andExpect(jsonPath("$.components.schemas.ChangePasswordRequest.properties.newPassword.maxLength").value(72))
                .andExpect(jsonPath("$.components.schemas.ResetPasswordRequest.properties.newPassword.minLength").value(8))
                .andExpect(jsonPath("$.components.schemas.ResetPasswordRequest.properties.newPassword.maxLength").value(72));
    }
}
