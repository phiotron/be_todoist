package com.taskly.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.DirectFieldBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.ServletWebRequest;

/**
 * Unit thuần (không Spring context): handler chỉ đặt KEY — title theo mã HTTP, detail theo ErrorMessage / ProblemTexts,
 * có type, không lộ chi tiết nội bộ. Câu vi / en do ProblemDetailLocalizer dịch (ProblemDetailLocalizerTest,
 * ProblemDetailLocalizationTest).
 */
class GlobalExceptionHandlerTest {

    private static final URI BLANK = URI.create("about:blank");

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    /** DTO mẫu: một message dạng {key}, một message viết cứng (như DTO chưa chuyển sang key). */
    static class Sample {
        @NotBlank(message = "{validation.email.required}")
        String email = "";

        @NotBlank(message = "Câu viết cứng")
        String name = " ";
    }

    private static ServletWebRequest request() {
        return new ServletWebRequest(new MockHttpServletRequest("POST", "/api/v1/auth/register"));
    }

    private static ProblemDetail problemOf(ResponseEntity<?> response) {
        assertThat(response.getBody()).isInstanceOf(ProblemDetail.class);
        return (ProblemDetail) response.getBody();
    }

    @Test
    void unexpectedException_returns500WithGenericDetailKey() {
        ResponseEntity<ProblemDetail> response =
                handler.handleUnexpected(new IllegalStateException("SELECT * FROM users WHERE email='a@b.c'"));

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(problem.getType()).isEqualTo(BLANK);
        assertThat(problem.getTitle()).isEqualTo("problem.title.500");
        assertThat(problem.getDetail()).isEqualTo("error.internal");
    }

    @Test
    void dataIntegrityViolation_returns409WithoutConstraintName() {
        ResponseEntity<ProblemDetail> response = handler.handleDataIntegrity(
                new DataIntegrityViolationException("duplicate key value violates unique constraint uq_users_email"));

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(problem.getType()).isEqualTo(BLANK);
        assertThat(problem.getTitle()).isEqualTo("problem.title.409");
        assertThat(problem.getDetail()).isEqualTo("error.data-conflict");
    }

    @Test
    void methodNotSupported_returns405WithDefaultDetailKey() throws Exception {
        ResponseEntity<Object> response =
                handler.handleException(new HttpRequestMethodNotSupportedException("DELETE"), request());

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(problem.getType()).isEqualTo(BLANK);
        assertThat(problem.getTitle()).isEqualTo("problem.title.405");
        assertThat(problem.getDetail()).isEqualTo("problem.detail.405");
    }

    @Test
    void mediaTypeNotSupported_returns415WithDefaultDetailKey() throws Exception {
        ResponseEntity<Object> response = handler.handleException(
                new HttpMediaTypeNotSupportedException(MediaType.TEXT_PLAIN, List.of(MediaType.APPLICATION_JSON)),
                request());

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(415);
        assertThat(problem.getTitle()).isEqualTo("problem.title.415");
        assertThat(problem.getDetail()).isEqualTo("problem.detail.415");
    }

    @Test
    void apiException_keepsItsDetailKeyAndGetsTypeAndTitleKey() throws Exception {
        ResponseEntity<Object> response =
                handler.handleException(ApiException.conflict(ErrorMessage.EMAIL_TAKEN), request());

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(problem.getType()).isEqualTo(BLANK);
        assertThat(problem.getTitle()).isEqualTo("problem.title.409");
        assertThat(problem.getDetail()).isEqualTo("error.email-taken");
    }

    @Test
    void authenticationException_returns401SessionInvalidKey() {
        ResponseEntity<ProblemDetail> response = handler.handleAuthentication(new BadCredentialsException("x"));

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(problem.getTitle()).isEqualTo("problem.title.401");
        assertThat(problem.getDetail()).isEqualTo("error.session-invalid");
    }

    @Test
    void messageKey_readsKeyFromConstraintTemplate_orKeepsHardcodedMessage() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        Sample sample = new Sample();
        DirectFieldBindingResult result = new DirectFieldBindingResult(sample, "sample");

        validator.validate(sample, result);

        assertThat(GlobalExceptionHandler.messageKey(result.getFieldError("email")))
                .isEqualTo("validation.email.required");
        assertThat(GlobalExceptionHandler.messageKey(result.getFieldError("name"))).isEqualTo("Câu viết cứng");
        validator.close();
    }

    @Test
    void messageKey_bindingFailureWithoutMessage_fallsBackToInvalidValueKey() {
        FieldError bindingFailure =
                new FieldError("sample", "age", "abc", true, new String[] {"typeMismatch"}, null, null);

        assertThat(GlobalExceptionHandler.messageKey(bindingFailure)).isEqualTo("validation.invalid");
    }

    @Test
    void accessDenied_returns403ProblemDetail() {
        ResponseEntity<ProblemDetail> response = handler.handleAccessDenied(new AccessDeniedException("denied"));

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(problem.getType()).isEqualTo(BLANK);
        assertThat(problem.getTitle()).isEqualTo("problem.title.403");
        assertThat(problem.getDetail()).isEqualTo("error.forbidden");
    }

    @Test
    void optimisticLock_returns409WithVersionConflictCode() {
        ResponseEntity<ProblemDetail> response =
                handler.handleOptimisticLock(new OptimisticLockingFailureException("row was updated"));

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(problem.getDetail()).isEqualTo("error.concurrent-update");
        assertThat(problem.getDetail()).doesNotContain("row");
        assertThat(problem.getProperties()).containsEntry("code", "version-conflict");
    }

    @Test
    void pessimisticLockFailures_return409WithCodeInsteadOf500() {
        for (RuntimeException failure : List.<RuntimeException>of(
                new DeadlockLoserDataAccessException("deadlock detected on relation projects", null),
                new CannotAcquireLockException("could not obtain lock on row in relation projects"))) {
            ResponseEntity<ProblemDetail> response = handler.handlePessimisticLock(
                    (PessimisticLockingFailureException) failure);

            ProblemDetail problem = problemOf(response);
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(problem.getType()).isEqualTo(BLANK);
            assertThat(problem.getTitle()).isEqualTo("problem.title.409");
            assertThat(problem.getDetail()).isEqualTo("error.concurrent-update");
            assertThat(problem.getDetail()).doesNotContain("projects");
            assertThat(problem.getProperties()).containsEntry("code", "version-conflict");
        }
    }

    @Test
    void apiException_unprocessable_has422TitleKey() throws Exception {
        ResponseEntity<Object> response =
                handler.handleException(ApiException.unprocessable(ErrorMessage.TARGET_ARCHIVED), request());

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(problem.getTitle()).isEqualTo("problem.title.422");
        assertThat(problem.getDetail()).isEqualTo("error.target-archived");
    }

    @Test
    void apiException_tooManyRequests_keepsRetryAfterHeader() throws Exception {
        ResponseEntity<Object> response =
                handler.handleException(ApiException.tooManyRequests(ErrorMessage.TOO_MANY_REQUESTS, 42), request());

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("42");
        assertThat(problemOf(response).getTitle()).isEqualTo("problem.title.429");
    }

    @Test
    void apiException_withProperty_addsFieldToBody() throws Exception {
        ResponseEntity<Object> response = handler.handleException(
                ApiException.conflict(ErrorMessage.VERSION_CONFLICT).withProperty("current", "bản mới"), request());

        ProblemDetail problem = problemOf(response);
        assertThat(problem.getProperties()).containsEntry("current", "bản mới");
        assertThat(problem.getDetail()).isEqualTo("error.version-conflict");
    }

    @Test
    void apiException_withCode_addsMachineReadableCode() throws Exception {
        ResponseEntity<Object> response = handler.handleException(
                ApiException.conflict(ErrorMessage.PROJECT_ARCHIVED).withCode(ProblemCodes.PROJECT_ARCHIVED), request());

        ProblemDetail problem = problemOf(response);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("code", "project-archived");
        assertThat(problem.getDetail()).isEqualTo("error.project-archived");
    }

    @Test
    void problemDetail_includesRequestIdFromMdc() {
        MDC.put("requestId", "rid-123");
        try {
            ProblemDetail problem = problemOf(handler.handleUnexpected(new IllegalStateException("x")));
            assertThat(problem.getProperties()).containsEntry("requestId", "rid-123");
        } finally {
            MDC.remove("requestId");
        }
    }

    @Test
    void frameworkProblemDetail_includesRequestIdFromMdc() throws Exception {
        MDC.put("requestId", "rid-456");
        try {
            ProblemDetail problem = problemOf(handler.handleException(ApiException.notFound(ErrorMessage.PROJECT_NOT_FOUND), request()));
            assertThat(problem.getProperties()).containsEntry("requestId", "rid-456");
        } finally {
            MDC.remove("requestId");
        }
    }
}
