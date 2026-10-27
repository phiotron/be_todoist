package com.taskly.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

/** Unit thuần: ApiException mang "code" (spec 12.10) và thuộc tính phụ qua handler, detail vẫn là key. */
class ApiExceptionCodeTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static ServletWebRequest request() {
        return new ServletWebRequest(new MockHttpServletRequest("POST", "/api/v1/me/2fa/setup"));
    }

    @Test
    void withCode_addsCodeProperty_andKeepsDetailAndTitleKeys() throws Exception {
        ResponseEntity<Object> response = handler.handleException(
                ApiException.conflict(ErrorMessage.TWO_FACTOR_ALREADY_ENABLED)
                        .withCode(ProblemCodes.TWO_FACTOR_ALREADY_ENABLED),
                request());

        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("problem.title.409");
        assertThat(problem.getDetail()).isEqualTo("error.two-factor-already-enabled");
        assertThat(problem.getProperties()).containsEntry("code", "two-factor-already-enabled");
    }

    @Test
    void withProperty_addsExtraPropertyNextToCode() throws Exception {
        ResponseEntity<Object> response = handler.handleException(
                ApiException.unauthorized(ErrorMessage.TWO_FACTOR_REQUIRED)
                        .withCode(ProblemCodes.TWO_FACTOR_REQUIRED)
                        .withProperty("twoFactorToken", "token-gia"),
                request());

        ProblemDetail problem = (ProblemDetail) response.getBody();
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(problem.getProperties())
                .containsEntry("code", "two-factor-required")
                .containsEntry("twoFactorToken", "token-gia");
    }

    @Test
    void problemCodes_matchSpecSection12_10() {
        assertThat(ProblemCodes.PROPERTY).isEqualTo("code");
        assertThat(List.of(ProblemCodes.TWO_FACTOR_REQUIRED, ProblemCodes.TWO_FACTOR_ALREADY_ENABLED,
                ProblemCodes.TWO_FACTOR_NOT_SETUP, ProblemCodes.PASSWORD_REQUIRED))
                .containsExactly("two-factor-required", "two-factor-already-enabled", "two-factor-not-setup",
                        "password-required");
    }
}
