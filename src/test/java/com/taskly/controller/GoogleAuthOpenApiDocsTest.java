package com.taskly.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** Hợp đồng OpenAPI của M5 — mobile sinh types.gen.ts từ đây (npm run gen:api). */
class GoogleAuthOpenApiDocsTest extends IntegrationTestBase {

    @Test
    void listsGoogleEndpointsWithCorrectSecurity() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/auth/google'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/auth/google'].post.security").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/v1/auth/google'].post.description",
                        containsString("link-required")))
                .andExpect(jsonPath("$.paths['/api/v1/me/identities/google'].post.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/v1/me/identities/google'].delete.security[0].bearerAuth")
                        .exists());
    }

    @Test
    void schemasMatchContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.GoogleSignInRequest.required")
                        .value(containsInAnyOrder("idToken")))
                .andExpect(jsonPath("$.components.schemas.GoogleLinkRequest.properties.idToken").exists())
                .andExpect(jsonPath("$.components.schemas.GoogleLinkRequest.properties.linkToken").exists())
                .andExpect(jsonPath("$.components.schemas.GoogleLinkRequest.required").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.hasPassword.type").value("boolean"))
                .andExpect(jsonPath("$.components.schemas.UserResponse.properties.linkedProviders.type")
                        .value("array"))
                .andExpect(jsonPath("$..linkedProviders..enum[*]", hasItem("GOOGLE")));
    }
}
