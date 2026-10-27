package com.taskly.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.taskly.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class RequestIdApiTest extends IntegrationTestBase {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Test
    void errorResponse_hasGeneratedRequestIdInHeaderAndBody() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        String header = result.getResponse().getHeader("X-Request-Id");
        assertThat(header).matches(UUID_PATTERN);
        assertThat((String) JsonPath.read(body(result), "$.requestId")).isEqualTo(header);
    }

    @Test
    void safeIncomingRequestId_isReused() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me").header("X-Request-Id", "app-req_123.4"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo("app-req_123.4");
        assertThat((String) JsonPath.read(body(result), "$.requestId")).isEqualTo("app-req_123.4");
    }

    @Test
    void unsafeIncomingRequestId_isReplaced() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/me").header("X-Request-Id", "bad id<script>"))
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Request-Id")).matches(UUID_PATTERN);
    }

    @Test
    void successResponse_alsoCarriesRequestId() throws Exception {
        RegisteredUser user = registerUser(uniqueEmail());

        MvcResult result = mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(user.accessToken())))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getHeader("X-Request-Id")).matches(UUID_PATTERN);
    }

    @Test
    void validationError_hasRequestId() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.requestId").isString());
    }
}
