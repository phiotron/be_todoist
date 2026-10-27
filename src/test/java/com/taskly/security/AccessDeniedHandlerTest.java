package com.taskly.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;

/** 403 do Spring Security sinh (AccessDeniedHandler) phải ra ProblemDetail như mọi lỗi khác. */
class AccessDeniedHandlerTest extends IntegrationTestBase {

    @Autowired
    private ProblemDetailAccessDeniedHandler accessDeniedHandler;

    @Test
    void handle_writes403ProblemDetailInVietnamese() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        MockHttpServletResponse response = new MockHttpServletResponse();

        accessDeniedHandler.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).contains("application/problem+json");
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("Không có quyền")
                .contains("Bạn không có quyền thực hiện thao tác này")
                .doesNotContain("denied");
    }
}
