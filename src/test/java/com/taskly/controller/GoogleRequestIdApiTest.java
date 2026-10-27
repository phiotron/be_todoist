package com.taskly.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.GoogleIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Mọi ProblemDetail kèm requestId = header X-Request-Id (spec 5.9) — kể cả 409 link-required có property riêng. */
class GoogleRequestIdApiTest extends GoogleIntegrationTestBase {

    @Test
    void googleErrorsCarryTheResponseRequestId() throws Exception {
        RegisteredUser existing = registerUser(uniqueEmail());
        MvcResult conflict = postGoogleSignIn(googleTokens.idToken().email(existing.email()).sign())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("link-required"))
                .andExpect(jsonPath("$.linkToken").isString())
                .andReturn();
        MvcResult invalid = postGoogleSignIn("not-a-jwt").andExpect(status().isUnauthorized()).andReturn();

        for (MvcResult result : new MvcResult[] {conflict, invalid}) {
            String requestId = JsonPath.read(body(result), "$.requestId");
            assertThat(requestId).isNotBlank().isEqualTo(result.getResponse().getHeader("X-Request-Id"));
        }
    }
}
